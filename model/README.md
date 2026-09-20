# `model/` — Phase 1: offline model + quantisation

Produces the weight/bias/golden artefacts the RTL consumes. No Chisel here.
See `../PLAN.md` "Phase 1" and open decision #3 (fixed-point convention).

## Pipeline

```
train.py  --float-only ─► checkpoints/float.pt        float baseline
train.py  (float→qat)  ─► checkpoints/qat.pt          INT4 weights + activations (QAT, STE)
train.py  --calibrate  ─► suggested per-tensor exponents (from real ranges)
export.py --ckpt qat.pt ─► artifacts/                  .hex + scales.json + golden.json
```

`refmodel.py` is the integer, tiled dataflow (32×32 passes, partial-sum buffer,
bias at psum width, ReLU, arithmetic-shift requant, argmax). It is the
bit-exact target for **Phase 2**'s Scala reference model — cross-check against
it on `artifacts/golden.json` before any RTL.

## Files

| file | role |
|---|---|
| `config.py` | shapes, tiling, widths, **fixed-point convention** (`FixedPointConfig`) |
| `quant.py` | hand-rolled fake-quant + STE; integer quantise / `round_shift` / requant |
| `data.py` | MNIST + pixel normalisation |
| `model.py` | the 784-64-10 `nn.Module`, float / QAT switch |
| `train.py` | float + QAT training, accuracy report, activation calibration |
| `refmodel.py` | integer tiled inference — the Phase 2 contract |
| `export.py` | `.hex` + `scales.json` + `golden.json` + `manifest.json` |
| `artifacts/` | **committed** outputs |
| `data/`, `checkpoints/` | gitignored, regenerated |

## Fixed-point convention (frozen 2026-09-13, PLAN.md open #3)

Per-tensor **power-of-two** scales. An INT4 code `q` means `x_real ≈ q · 2^e`;
one integer `e` per tensor (`config.FixedPointConfig`).

```
quantise :  q = clamp(round_half_up(x / 2^e), -8, 7)
VMM pass :  acc_int exact in INT13; its scale is 2^(e_w + e_a)
bias     :  b_int = round(b_real / 2^(e_w + e_a)), added at psum width (not INT4)
ReLU     :  max(acc_int + b_int, 0)                          (layer 1 only)
requant  :  shift = e_a_next - (e_w + e_a)   [must be ≥ 0]
            h_int = clamp(round_shift(psum_int, shift), -8, 7)
argmax   :  over layer-2's 10 real lanes (scale-invariant, no requant)
```

`round_shift(x, s) = (x + (1<<(s-1))) >> s` for `s>0`, arithmetic — the exact
target for the Scala requant and the Verilog datapath.

The exponents in `config.FXP` are **frozen** (MSE-calibrated via
`calibrate.py`, committed in `artifacts/scales.json`): `e_act_in=-1, e_w1=-5,
e_act_hidden=1, e_w2=-3` → `requant_shift=7`. Measured accuracy at this
convention: float 97.70%, INT4 97.23% (Δ +0.47pp). Re-run
`calibrate.py --write` only if the float model is retrained.

## Weight `.hex` layout (loader contract for Phase 3 `WMC`)

One INT4 nibble per line (two's complement, `-8..7 → 0x8..0xF, 0x0..0x7`):

```
line = ((o · input_tiles + k) · DIM + row) · DIM + col
W[row, col] = fc.weight[o·DIM + col, k·DIM + row]        (nn.Linear is out×in)
```

`o` output-tile, `k` input-tile, `row` = `weightRow` address, `col` =
`weightRowData` index. Padded lanes are 0. Combined weight RAM =
`layer1_w.hex` (50 tiles) then `layer2_w.hex` (2 tiles).

## Quick start

```
pip install -r requirements.txt --index-url https://download.pytorch.org/whl/cpu
python train.py                              # ~1 min on CPU
python train.py --calibrate checkpoints/float.pt
# edit config.FXP exponents if calibration disagrees, then:
python train.py --qat-from checkpoints/float.pt
python export.py --ckpt checkpoints/qat.pt
```
