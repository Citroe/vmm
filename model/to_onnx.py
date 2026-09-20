"""Export the trained MLP to ONNX for a Netron topology sanity-check (PLAN Phase 1).

    python to_onnx.py                                  # float graph -> mlp_float.onnx
    python to_onnx.py --ckpt checkpoints/qat.pt --quant --out mlp_qat.onnx

What Netron shows: the op graph + tensor shapes. Use it to confirm the topology
matches `config.LAYERS` --

    image_784  ->  Gemm (784x64)  ->  Relu  ->  Gemm (64x10)  ->  logits_10

and that the weight tensors are 64x784 and 10x64 (nn.Linear is out x in).

What Netron does NOT show: the INT4 fixed-point convention (per-tensor 2**e
scales, the psum-width bias, the arithmetic-shift requant). That lives in
`config.FXP` / `artifacts/scales.json` and is checked by `refmodel.py` and the
Scala `MlpRefModelSpec`, not here. `--quant` just traces the QAT module as-is
(the fake-quant shows up as Div/Round/Clamp/Mul clusters around each Gemm); the
plain float graph is the cleaner thing to eyeball for topology.

Viewing:

    pip install netron
    netron mlp_float.onnx            # opens a browser tab (Ctrl-C to stop)
    # or, headless / remote:
    python -c "import netron; netron.start('mlp_float.onnx', address=('0.0.0.0', 8080))"

The .onnx is a dev-convenience artefact, not an RTL input -- it is gitignored
(see model/.gitignore), regenerate it from a checkpoint whenever you need it.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from config import LAYER1, ROOT
from model import MLP


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default="checkpoints/float.pt",
                    help="checkpoint to load (default: checkpoints/float.pt)")
    ap.add_argument("--quant", action="store_true",
                    help="trace the QAT module (fake-quant ops included in the graph)")
    ap.add_argument("--out", default="mlp_float.onnx",
                    help="output .onnx path (default: mlp_float.onnx, next to this script)")
    args = ap.parse_args()

    ckpt = (ROOT / args.ckpt) if not Path(args.ckpt).is_absolute() else Path(args.ckpt)
    out = (ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)

    m = MLP(quant=args.quant)
    m.load_state_dict(torch.load(ckpt, map_location="cpu"))
    m.eval()

    dummy = torch.zeros(1, LAYER1.k)
    out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        m, dummy, out.as_posix(),
        input_names=["image_784"], output_names=["logits_10"],
        opset_version=18,
        external_data=False,  # single self-contained .onnx (no sidecar .onnx.data)
    )
    print(f"wrote {out}  (from {ckpt}, quant={args.quant})")
    print(f"view:  netron {out}")


if __name__ == "__main__":
    main()
