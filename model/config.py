"""Phase 1 configuration -- shapes, tiling, widths, fixed-point convention.

Every constant the offline flow shares with the RTL lives here so there is a
single source of truth to reconcile against `src/main/scala/vmm/VmmParams.scala`
and PLAN.md's "layer mapping" table.

Nothing in this file is trained; it is the fixed contract the training,
export, and reference-model code all target.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# VMM core geometry -- must match VmmParams(dim=32, inWidth=4)
# ---------------------------------------------------------------------------

DIM = 32            # VMM is DIM x DIM per pass
IN_WIDTH = 4        # signed INT4 weights and activations
PROD_WIDTH = 2 * IN_WIDTH               # 8
ACC_WIDTH = PROD_WIDTH + (DIM - 1).bit_length()   # 8 + 5 = 13, matches VmmParams

INT4_MIN = -(1 << (IN_WIDTH - 1))       # -8
INT4_MAX = (1 << (IN_WIDTH - 1)) - 1    #  7


# ---------------------------------------------------------------------------
# MLP topology (MNIST 784 -> 64 -> 10)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class LayerShape:
    name: str
    k: int              # contraction (input) dimension
    n: int              # output dimension
    relu: bool          # ReLU after bias (layer 1 only)

    @property
    def input_tiles(self) -> int:
        return (self.k + DIM - 1) // DIM

    @property
    def output_tiles(self) -> int:
        return (self.n + DIM - 1) // DIM

    @property
    def k_padded(self) -> int:
        return self.input_tiles * DIM

    @property
    def n_padded(self) -> int:
        return self.output_tiles * DIM

    @property
    def passes(self) -> int:
        return self.input_tiles * self.output_tiles

    @property
    def psum_width(self) -> int:
        # accWidth + ceil(log2(input_tiles)) -- wide enough for the cross-tile
        # sum held in MlpEngine's partial-sum buffer.
        extra = max(0, (self.input_tiles - 1).bit_length())
        return ACC_WIDTH + extra


LAYER1 = LayerShape(name="layer1", k=784, n=64, relu=True)
LAYER2 = LayerShape(name="layer2", k=64, n=10, relu=False)
LAYERS = (LAYER1, LAYER2)

# Sanity vs PLAN.md's table.
assert LAYER1.input_tiles == 25 and LAYER1.output_tiles == 2 and LAYER1.passes == 50
assert LAYER2.input_tiles == 2 and LAYER2.output_tiles == 1 and LAYER2.passes == 2
assert LAYER1.psum_width == 18 and LAYER2.psum_width == 14


# ---------------------------------------------------------------------------
# Fixed-point convention  ("FP -> fxd. F", PLAN.md open decision #3)
# ---------------------------------------------------------------------------
#
# Per-tensor, power-of-two scales.  An INT4 code `q` represents the real value
#
#       x_real  ~=  q * 2**e            (e is a negative integer, "exponent")
#
# so a whole tensor is described by one integer `e`.  Quantise with
#
#       q = clamp(round(x_real / 2**e), INT4_MIN, INT4_MAX)
#
# Choosing power-of-two scales is what makes the between-layer requant a pure
# arithmetic shift (no multiplier), per PLAN.md.
#
# Accumulator scale after a VMM pass is  2**(e_w + e_a)  (product of the weight
# and activation exponents).  Bias is pre-scaled to that same fixed-point and
# added at the psum width, NOT quantised to INT4:
#
#       b_int = round(b_real / 2**(e_w + e_a))          (signed, psum_width bits)
#
# Between layers (layer 1 -> layer 2 here) the psum is requantised back to an
# INT4 activation at the next layer's activation exponent e_a_next:
#
#       shift  = e_a_next - (e_w + e_a)         # REQUIRED >= 0 (right shift)
#       h_int  = clamp( round_shift(psum_int, shift), INT4_MIN, INT4_MAX )
#
# round_shift is arithmetic-shift-right with round-half-up:
#
#       round_shift(x, s) = (x + (1 << (s-1))) >> s      for s > 0
#       round_shift(x, 0) = x
#
# The final layer produces logits; argmax over the 10 real lanes is
# scale-invariant, so no requant there.
#
# The exponents below are frozen from MSE-optimal power-of-two calibration on
# checkpoint float.pt (calibrate.py) and committed alongside the .hex
# artefacts in artifacts/scales.json. The `shift >= 0` constraint is asserted
# at export time. Re-run calibrate.py if the float model is retrained.

@dataclass
class FixedPointConfig:
    # exponent e such that value ~= code * 2**e. MSE-optimal power-of-two per
    # tensor (calibrate.py): finer than max-abs, accepts a few % tail clipping.
    e_act_in: int = -1       # normalised pixels; ~0% clip
    e_w1: int = -5           # ~3% clip; 12x lower MSE than the max-abs e=-2
    e_act_hidden: int = 1    # layer-1 ReLU output, ~2.8% clip -> requant shift 7
    e_w2: int = -3

    def acc_exp(self, layer_idx: int) -> int:
        """Exponent of the raw INT accumulator for layer 0 or 1."""
        if layer_idx == 0:
            return self.e_w1 + self.e_act_in
        if layer_idx == 1:
            return self.e_w2 + self.e_act_hidden
        raise IndexError(layer_idx)

    @property
    def requant_shift(self) -> int:
        """layer-1 psum -> hidden INT4 activation. Must be >= 0."""
        return self.e_act_hidden - self.acc_exp(0)


FXP = FixedPointConfig()


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"                 # MNIST download cache (gitignored)
ARTIFACT_DIR = ROOT / "artifacts"        # committed: .hex, scales, golden vectors
CKPT_DIR = ROOT / "checkpoints"          # *.pt (gitignored)

for _d in (DATA_DIR, ARTIFACT_DIR, CKPT_DIR):
    _d.mkdir(exist_ok=True)


# ---------------------------------------------------------------------------
# Training hyperparameters (iterate freely -- not part of the HW contract)
# ---------------------------------------------------------------------------

@dataclass
class TrainConfig:
    epochs_float: int = 15
    epochs_qat: int = 10
    batch_size: int = 128
    lr: float = 1e-3
    weight_decay: float = 1e-4
    seed: int = 0
    hidden: int = LAYER1.n            # 64
    # pixel normalisation: (x/255 - mean) / std  -> roughly [-1, 1)
    pixel_mean: float = 0.1307
    pixel_std: float = 0.3081
    n_golden: int = 16               # test images exported as golden vectors


TRAIN = TrainConfig()
