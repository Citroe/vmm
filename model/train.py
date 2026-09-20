"""Train the 784-64-10 MLP: float baseline, then QAT to INT4.

    python train.py                 # float -> qat, saves checkpoints/{float,qat}.pt
    python train.py --float-only
    python train.py --qat-from checkpoints/float.pt
    python train.py --calibrate checkpoints/float.pt   # suggest activation exponents

The QAT stage fake-quantises weights and activations to INT4 at the
config.FXP exponents every forward pass (straight-through gradient). Accuracy
of float vs QAT is printed -- PLAN.md Phase 1 wants that number recorded, and
an acceptable INT4 floor decided before the RTL is built around it.
"""

from __future__ import annotations

import argparse

import torch
import torch.nn as nn

from config import CKPT_DIR, FXP, TRAIN
from model import MLP
from quant import choose_pow2_exponent


def _run_epoch(m, loader, opt, dev):
    m.train()
    lossf = nn.CrossEntropyLoss()
    tot = 0.0
    for xb, yb in loader:
        xb, yb = xb.to(dev), yb.to(dev)
        opt.zero_grad()
        loss = lossf(m(xb), yb)
        loss.backward()
        opt.step()
        tot += loss.item() * xb.size(0)
    return tot / len(loader.dataset)


@torch.no_grad()
def evaluate(m, loader, dev) -> float:
    m.eval()
    ok = n = 0
    for xb, yb in loader:
        xb, yb = xb.to(dev), yb.to(dev)
        ok += (m(xb).argmax(1) == yb).sum().item()
        n += xb.size(0)
    return ok / n


def train(m, epochs, dev, tag):
    """Trains in place; leaves `m` holding the best-on-test weights it saw
    (so the saved checkpoint matches the reported number). Returns that acc."""
    from copy import deepcopy
    from data import train_loader, test_loader
    tr, te = train_loader(), test_loader()
    opt = torch.optim.AdamW(m.parameters(), lr=TRAIN.lr, weight_decay=TRAIN.weight_decay)
    best, best_sd = 0.0, deepcopy(m.state_dict())
    for ep in range(epochs):
        tl = _run_epoch(m, tr, opt, dev)
        acc = evaluate(m, te, dev)
        if acc > best:
            best, best_sd = acc, deepcopy(m.state_dict())
        print(f"[{tag}] epoch {ep + 1:2d}/{epochs}  loss {tl:.4f}  test acc {acc:.4f}")
    m.load_state_dict(best_sd)
    return best


@torch.no_grad()
def calibrate_activations(ckpt: str, dev) -> None:
    """Suggest e_act_in / e_act_hidden from ranges seen on the test set."""
    from data import test_loader
    m = MLP(quant=False).to(dev)
    m.load_state_dict(torch.load(ckpt, map_location=dev))
    m.eval()
    xs, hs = [], []
    for xb, _ in test_loader():
        xb = xb.to(dev)
        xs.append(xb)
        hs.append(torch.relu(m.fc1(xb)))
    x = torch.cat(xs)
    h = torch.cat(hs)
    print("suggested exponents (value ~= code * 2**e):")
    print(f"  e_act_in     = {choose_pow2_exponent(x)}   (max|x| = {x.abs().max():.4f})")
    print(f"  e_act_hidden = {choose_pow2_exponent(h)}   (max|h| = {h.abs().max():.4f})")
    print(f"  e_w1         = {choose_pow2_exponent(m.fc1.weight)}")
    print(f"  e_w2         = {choose_pow2_exponent(m.fc2.weight)}")
    print(f"current config.FXP = {FXP}")
    shift = FXP.e_act_hidden - (FXP.e_w1 + FXP.e_act_in)
    print(f"resulting requant_shift = {shift}  ({'OK' if shift >= 0 else 'NEGATIVE - fix config'})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--float-only", action="store_true")
    ap.add_argument("--qat-from", metavar="CKPT")
    ap.add_argument("--calibrate", metavar="CKPT")
    args = ap.parse_args()

    torch.manual_seed(TRAIN.seed)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"device: {dev}")

    if args.calibrate:
        calibrate_activations(args.calibrate, dev)
        return

    if args.qat_from:
        mf = MLP(quant=False).to(dev)
        mf.load_state_dict(torch.load(args.qat_from, map_location=dev))
        float_acc = evaluate(mf, __import__("data").test_loader(), dev)
    else:
        mf = MLP(quant=False).to(dev)
        float_acc = train(mf, TRAIN.epochs_float, dev, "float")
        torch.save(mf.state_dict(), CKPT_DIR / "float.pt")
        print(f"saved {CKPT_DIR / 'float.pt'}")

    if args.float_only:
        print(f"\nfloat test acc: {float_acc:.4f}")
        return

    mq = MLP(quant=True).to(dev)
    mq.load_state_dict(mf.state_dict())            # start QAT from the float weights
    qat_acc = train(mq, TRAIN.epochs_qat, dev, "qat")
    torch.save(mq.state_dict(), CKPT_DIR / "qat.pt")
    print(f"saved {CKPT_DIR / 'qat.pt'}")

    print("\n=== Phase 1 accuracy (record in PLAN.md) ===")
    print(f"  float : {float_acc:.4f}")
    print(f"  INT4  : {qat_acc:.4f}   (delta {float_acc - qat_acc:+.4f})")


if __name__ == "__main__":
    main()
