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
    python export.py --ckpt checkpoints/qat.pt            # export
    python export.py --ckpt checkpoints/qat.pt --calibrate  # print suggested exponents, no write
"""

from __future__ import annotations

import argparse
import json
import hashlib
from dataclasses import asdict

import numpy as np
import torch

from config import (ARTIFACT_DIR, DIM, FXP, INT4_MIN, INT4_MAX, LAYER1, LAYER2,
                    LAYERS, TRAIN)
from model import MLP
from quant import (choose_pow2_exponent, quantize_int, quantize_bias_int,
                   requant_to_int4)
import refmodel


def tile_weight(weight: np.ndarray, shape) -> np.ndarray:
    """nn.Linear weight (N, K) -> (output_tiles, input_tiles, DIM, DIM) INT4-free
    float, zero-padded. Caller quantises."""
    n, k = weight.shape
    assert (n, k) == (shape.n, shape.k)
    out = np.zeros((shape.output_tiles, shape.input_tiles, DIM, DIM), dtype=np.float64)
    for o in range(shape.output_tiles):
        for kt in range(shape.input_tiles):
            for col in range(DIM):
                nn_ = o * DIM + col
                if nn_ >= n:
                    continue
                for row in range(DIM):
                    kk = kt * DIM + row
                    if kk >= k:
                        continue
                    out[o, kt, row, col] = weight[nn_, kk]
    return out


def _nib(v: int) -> str:
    return f"{v & 0xF:x}"


def write_weight_hex(path, tiles_int: np.ndarray) -> None:
    ot, it, d0, d1 = tiles_int.shape
    with open(path, "w") as f:
        for o in range(ot):
            for k in range(it):
                for row in range(d0):
                    for col in range(d1):
                        f.write(_nib(int(tiles_int[o, k, row, col])) + "\n")


def write_bias_hex(path, bias_int: np.ndarray, width: int) -> None:
    mask = (1 << width) - 1
    with open(path, "w") as f:
        for v in bias_int:
            f.write(f"{int(v) & mask:0{(width + 3) // 4}x}\n")


def pack_params(m: MLP) -> dict:
    """Quantise + tile everything the integer reference model needs."""
    w1 = m.fc1.weight.detach().numpy()
    w2 = m.fc2.weight.detach().numpy()
    b1 = m.fc1.bias.detach().numpy()
    b2 = m.fc2.bias.detach().numpy()

    w1_t = quantize_int(tile_weight(w1, LAYER1), FXP.e_w1)
    w2_t = quantize_int(tile_weight(w2, LAYER2), FXP.e_w2)
    b1_i = quantize_bias_int(b1, FXP.acc_exp(0), LAYER1.psum_width)
    b2_i = quantize_bias_int(b2, FXP.acc_exp(1), LAYER2.psum_width)
    return {"w1_tiles": w1_t, "w2_tiles": w2_t, "b1_int": b1_i, "b2_int": b2_i}


def calibrate(m: MLP) -> dict:
    """Suggest per-tensor exponents from the trained weights (activations need a
    calibration pass over data -- see train.py --calibrate)."""
    return {
        "e_w1": choose_pow2_exponent(m.fc1.weight),
        "e_w2": choose_pow2_exponent(m.fc2.weight),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--calibrate", action="store_true",
                    help="print suggested exponents and exit")
    args = ap.parse_args()

    m = MLP(quant=True)
    m.load_state_dict(torch.load(args.ckpt, map_location="cpu"))
    m.eval()

    if args.calibrate:
        print("suggested weight exponents:", calibrate(m))
        print("current config.FXP        :", asdict(FXP))
        print("requant_shift             :", FXP.requant_shift)
        return

    if FXP.requant_shift < 0:
        raise SystemExit(
            f"FXP.requant_shift = {FXP.requant_shift} < 0: the between-layer "
            "requant would be a LEFT shift. Adjust e_act_hidden / e_w1 / "
            "e_act_in in config.py so e_act_hidden >= e_w1 + e_act_in.")

    params = pack_params(m)

    write_weight_hex(ARTIFACT_DIR / "layer1_w.hex", params["w1_tiles"])
    write_weight_hex(ARTIFACT_DIR / "layer2_w.hex", params["w2_tiles"])
    write_bias_hex(ARTIFACT_DIR / "layer1_b.hex", params["b1_int"], LAYER1.psum_width)
    write_bias_hex(ARTIFACT_DIR / "layer2_b.hex", params["b2_int"], LAYER2.psum_width)

    (ARTIFACT_DIR / "scales.json").write_text(json.dumps({
        **asdict(FXP),
        "requant_shift": FXP.requant_shift,
        "acc_exp_layer1": FXP.acc_exp(0),
        "acc_exp_layer2": FXP.acc_exp(1),
        "note": "value_real ~= int_code * 2**e ; requant = round_shift(psum, requant_shift)",
    }, indent=2))

    # --- golden vectors ---
    from data import test_tensors
    X, y = test_tensors()
    idx = list(range(TRAIN.n_golden))
    golden = []
    for i in idx:
        img_i4 = quantize_int(X[i].numpy(), FXP.e_act_in)
        ref = refmodel.infer(img_i4, params)
        with torch.no_grad():
            qat_logits = m(X[i:i + 1]).numpy().ravel().tolist()  # fake-quant float path
        golden.append({
            "index": i,
            "label": int(y[i]),
            "image_int4": img_i4.tolist(),
            "hidden_int4": ref["hidden_int4"].tolist(),
            "logits_int": ref["logits"].tolist(),
            "pred": ref["pred"],
            "qat_float_logits": qat_logits,
        })
    (ARTIFACT_DIR / "golden.json").write_text(json.dumps(golden, indent=2))

    def sha(p):
        return hashlib.sha256((ARTIFACT_DIR / p).read_bytes()).hexdigest()[:16]

    (ARTIFACT_DIR / "manifest.json").write_text(json.dumps({
        "dim": DIM, "int4_range": [INT4_MIN, INT4_MAX],
        "layers": [{"name": s.name, "k": s.k, "n": s.n,
                    "input_tiles": s.input_tiles, "output_tiles": s.output_tiles,
                    "passes": s.passes, "psum_width": s.psum_width} for s in LAYERS],
        "weight_line_index": "((o*input_tiles + k)*DIM + row)*DIM + col",
        "combined_weight_order": ["layer1_w.hex", "layer2_w.hex"],
        "files": {p: sha(p) for p in ["layer1_w.hex", "layer2_w.hex",
                                      "layer1_b.hex", "layer2_b.hex",
                                      "scales.json", "golden.json"]},
    }, indent=2))

    n_pass = sum(1 for g_ in golden if g_["pred"] == g_["label"])
    print(f"wrote artefacts to {ARTIFACT_DIR}")
    print(f"golden set: {n_pass}/{len(golden)} correct (integer reference model)")


if __name__ == "__main__":
    main()
