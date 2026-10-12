"""Export a trained (QAT) MLP to the artefacts the RTL consumes.

Outputs (all in model/artifacts/, all committed):
  layer1_w.hex  layer2_w.hex   INT4 weights, one nibble per line, tile-major
  layer1_b.hex  layer2_b.hex   integer bias at the psum fixed-point, one per line
  scales.json                  the frozen fixed-point exponents + shifts
  golden.json                  N test images (INT4) + expected hidden/logits/pred
  manifest.json                shapes, tile counts, layout formula, checksums

Weight-tile index (per layer), matching the loader contract Phase 3 (WMC) must
honour:

    line = ((o * input_tiles + k) * DIM + row) * DIM + col

  o   in [0, output_tiles)   output-tile
  k   in [0, input_tiles)    input-tile
  row in [0, DIM)            contraction index within the tile  (weightRow addr)
  col in [0, DIM)            output lane within the tile        (weightRowData idx)

  value = two's-complement INT4 as a hex nibble (-8..7 -> 0x8..0xF,0x0..0x7)
  W[row, col] = fc.weight[o*DIM + col, k*DIM + row]   (nn.Linear is out x in)
  padded lanes (col >= N or row >= K) are 0.

The combined weight RAM for Phase 3 is layer1_w.hex followed by layer2_w.hex
(50 + 2 = 52 tiles).

Usage:
    python export.py --ckpt checkpoints/qat.pt
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict
from pathlib import Path

import numpy as np
import torch

import refmodel
from config import (ARTIFACT_DIR, DIM, FXP, INT4_MIN, INT4_MAX, LAYER1, LAYER2,
                    LAYERS, TRAIN, LayerShape)
from data import test_tensors
from model import MLP
from quant import quantize_int, quantize_bias_int

HASHED_FILES = ["layer1_w.hex", "layer2_w.hex", "layer1_b.hex", "layer2_b.hex",
                "scales.json", "golden.json"]


def tile_weight(weight: np.ndarray, shape: LayerShape) -> np.ndarray:
    """nn.Linear weight (N, K) -> (output_tiles, input_tiles, DIM, DIM) float,
    zero-padded, with out[o, k, row, col] = weight[o*DIM + col, k*DIM + row].
    Caller quantises."""
    assert weight.shape == (shape.n, shape.k)
    padded = np.zeros((shape.n_padded, shape.k_padded), dtype=np.float64)
    padded[: shape.n, : shape.k] = weight
    # (o, col, k, row) -> (o, k, row, col)
    return padded.reshape(shape.output_tiles, DIM, shape.input_tiles, DIM).transpose(0, 2, 3, 1)


def write_hex(path: Path, values: np.ndarray, width: int) -> None:
    """One two's-complement `width`-bit value per line, in C (row-major) order."""
    mask = (1 << width) - 1
    digits = (width + 3) // 4
    path.write_text("".join(f"{v & mask:0{digits}x}\n" for v in values.reshape(-1).tolist()))


def pack_params(m: MLP) -> dict:
    """Quantise + tile everything the integer reference model needs."""
    w1 = m.fc1.weight.detach().numpy()
    w2 = m.fc2.weight.detach().numpy()
    b1 = m.fc1.bias.detach().numpy()
    b2 = m.fc2.bias.detach().numpy()

    return {
        "w1_tiles": quantize_int(tile_weight(w1, LAYER1), FXP.e_w1),
        "w2_tiles": quantize_int(tile_weight(w2, LAYER2), FXP.e_w2),
        "b1_int": quantize_bias_int(b1, FXP.acc_exp(0), LAYER1.psum_width),
        "b2_int": quantize_bias_int(b2, FXP.acc_exp(1), LAYER2.psum_width),
    }


def load_qat(ckpt: str | Path) -> MLP:
    m = MLP(quant=True)
    m.load_state_dict(torch.load(ckpt, map_location="cpu"))
    m.eval()
    return m


def scales() -> dict:
    return {
        **asdict(FXP),
        "requant_shift": FXP.requant_shift,
        "acc_exp_layer1": FXP.acc_exp(0),
        "acc_exp_layer2": FXP.acc_exp(1),
        "note": "value_real ~= int_code * 2**e ; requant = round_shift(psum, requant_shift)",
    }


@torch.no_grad()
def golden_vectors(m: MLP, params: dict, n: int) -> list[dict]:
    """First `n` test images through the integer reference model."""
    X, y = test_tensors()
    golden = []
    for i in range(n):
        img_i4 = quantize_int(X[i].numpy(), FXP.e_act_in)
        ref = refmodel.infer(img_i4, params)
        golden.append({
            "index": i,
            "label": int(y[i]),
            "image_int4": img_i4.tolist(),
            "hidden_int4": ref["hidden_int4"].tolist(),
            "logits_int": ref["logits"].tolist(),
            "pred": int(ref["pred"]),
            "qat_float_logits": m(X[i:i + 1]).numpy().ravel().tolist(),  # fake-quant float path
        })
    return golden


def manifest() -> dict:
    def sha(name: str) -> str:
        return hashlib.sha256((ARTIFACT_DIR / name).read_bytes()).hexdigest()[:16]

    return {
        "dim": DIM, "int4_range": [INT4_MIN, INT4_MAX],
        "layers": [{"name": s.name, "k": s.k, "n": s.n,
                    "input_tiles": s.input_tiles, "output_tiles": s.output_tiles,
                    "passes": s.passes, "psum_width": s.psum_width} for s in LAYERS],
        "weight_line_index": "((o*input_tiles + k)*DIM + row)*DIM + col",
        "combined_weight_order": ["layer1_w.hex", "layer2_w.hex"],
        "files": {name: sha(name) for name in HASHED_FILES},
    }


def write_json(name: str, obj) -> None:
    (ARTIFACT_DIR / name).write_text(json.dumps(obj, indent=2))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    args = ap.parse_args()

    if FXP.requant_shift < 0:
        raise SystemExit(
            f"FXP.requant_shift = {FXP.requant_shift} < 0: the between-layer "
            "requant would be a LEFT shift. Adjust e_act_hidden / e_w1 / "
            "e_act_in in config.py so e_act_hidden >= e_w1 + e_act_in.")

    m = load_qat(args.ckpt)
    params = pack_params(m)

    write_hex(ARTIFACT_DIR / "layer1_w.hex", params["w1_tiles"], 4)
    write_hex(ARTIFACT_DIR / "layer2_w.hex", params["w2_tiles"], 4)
    write_hex(ARTIFACT_DIR / "layer1_b.hex", params["b1_int"], LAYER1.psum_width)
    write_hex(ARTIFACT_DIR / "layer2_b.hex", params["b2_int"], LAYER2.psum_width)
    write_json("scales.json", scales())

    golden = golden_vectors(m, params, TRAIN.n_golden)
    write_json("golden.json", golden)
    write_json("manifest.json", manifest())     # last: it hashes the files above

    n_pass = sum(g["pred"] == g["label"] for g in golden)
    print(f"wrote artefacts to {ARTIFACT_DIR}")
    print(f"golden set: {n_pass}/{len(golden)} correct (integer reference model)")


if __name__ == "__main__":
    main()
