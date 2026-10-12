"""Measure saved checkpoints on the full MNIST test set, three ways:

    float     checkpoints/float.pt, plain float32 forward pass
    QAT       checkpoints/qat.pt, fake-quant float forward pass (what training sees)
    integer   checkpoints/qat.pt through refmodel.py -- the tiled INT4 dataflow
              the RTL is bit-exact against, i.e. the accuracy the hardware gets

    python evaluate.py
    python evaluate.py --float checkpoints/float.pt --qat checkpoints/qat.pt

QAT and integer differ only where the float bias / float accumulation of the
fake-quant path rounds differently from the integer one; the last line reports
how many test images the two disagree on.
"""

from __future__ import annotations

import argparse

import torch

import refmodel
from config import CKPT_DIR, FXP
from data import test_tensors
from export import load_qat, pack_params
from model import MLP
from quant import quantize_int


@torch.no_grad()
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--float", dest="float_ckpt", default=CKPT_DIR / "float.pt")
    ap.add_argument("--qat", dest="qat_ckpt", default=CKPT_DIR / "qat.pt")
    args = ap.parse_args()

    X, y = test_tensors()
    y = y.numpy()

    mf = MLP(quant=False)
    mf.load_state_dict(torch.load(args.float_ckpt, map_location="cpu"))
    mf.eval()
    float_pred = mf(X).argmax(1).numpy()

    mq = load_qat(args.qat_ckpt)
    qat_pred = mq(X).argmax(1).numpy()

    images_int4 = quantize_int(X.numpy(), FXP.e_act_in)
    int_pred = refmodel.infer(images_int4, pack_params(mq))["pred"]

    n = len(y)
    print(f"test images: {n}")
    for name, pred in (("float", float_pred), ("QAT", qat_pred), ("integer", int_pred)):
        print(f"  {name:8s}: {(pred == y).mean():.4f}")
    print(f"QAT vs integer disagreements: {(qat_pred != int_pred).sum()}/{n}")


if __name__ == "__main__":
    main()
