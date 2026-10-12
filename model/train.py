"""Train the 784-64-10 MLP: float baseline, then QAT to INT4.

    python train.py                 # float -> qat, saves checkpoints/{float,qat}.pt
    python train.py --float-only
    python train.py --qat-from checkpoints/float.pt

The QAT stage fake-quantises weights and activations to INT4 at the
config.FXP exponents every forward pass (straight-through gradient). Accuracy
of float vs QAT is printed; `evaluate.py` re-measures saved checkpoints,
including the integer reference model the RTL actually matches.
"""

from __future__ import annotations

import argparse
from copy import deepcopy

import torch
import torch.nn as nn

from config import CKPT_DIR, TRAIN
from data import test_loader, train_loader
from model import MLP


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


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--float-only", action="store_true")
    ap.add_argument("--qat-from", metavar="CKPT")
    args = ap.parse_args()

    torch.manual_seed(TRAIN.seed)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"device: {dev}")

    mf = MLP(quant=False).to(dev)
    if args.qat_from:
        mf.load_state_dict(torch.load(args.qat_from, map_location=dev))
        float_acc = evaluate(mf, test_loader(), dev)
    else:
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
