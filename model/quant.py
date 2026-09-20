"""Fake-quant primitives with a straight-through estimator.

Deliberately hand-rolled rather than `torch.ao.quantization`: Phase 2 has to
reproduce these exact semantics bit-for-bit in Scala, so the rounding mode,
clamp bounds, and power-of-two scale rule all need to be visible and pinned
here in a few lines.

Semantics (see config.py for the full convention):
    q      = clamp(round_half_up(x / 2**e), INT4_MIN, INT4_MAX)
    x_hat  = q * 2**e                              # dequantised, for the fwd pass
    grad   = passes straight through where |x/2**e| was in range, else 0
"""

from __future__ import annotations

import numpy as np
import torch

from config import INT4_MIN, INT4_MAX


def choose_pow2_exponent(x: torch.Tensor | np.ndarray, headroom_codes: int = INT4_MAX) -> int:
    """Smallest integer e such that  max|x| / 2**e  <=  headroom_codes.

    i.e. the tensor's largest magnitude maps to at most `headroom_codes`
    (default 7 = INT4_MAX, so nothing clamps). Calibration for the per-tensor
    scale; the result goes into config.FixedPointConfig.
    """
    x = torch.as_tensor(x).detach().float()
    m = float(x.abs().max())
    if m == 0.0:
        return -7
    import math
    return math.ceil(math.log2(m / headroom_codes))


def round_half_up(x: torch.Tensor) -> torch.Tensor:
    """floor(x + 0.5) -- ties go toward +inf. Matches round_shift() and the
    intended Verilog `(x + bias) >>> s`."""
    return torch.floor(x + 0.5)


class _FakeQuant(torch.autograd.Function):
    @staticmethod
    def forward(ctx, x: torch.Tensor, e: int):
        scale = 2.0 ** e
        q = torch.clamp(round_half_up(x / scale), INT4_MIN, INT4_MAX)
        # in-range mask for the STE (clamped values get no gradient)
        ctx.save_for_backward((q > INT4_MIN) & (q < INT4_MAX) | (x / scale).abs().le(INT4_MAX))
        return q * scale

    @staticmethod
    def backward(ctx, g):
        (mask,) = ctx.saved_tensors
        return g * mask, None


def fake_quant(x: torch.Tensor, e: int) -> torch.Tensor:
    """Differentiable quantise->dequantise at exponent `e`."""
    return _FakeQuant.apply(x, e)


# --- integer-domain helpers (numpy, used by export.py and refmodel.py) ---

def quantize_int(x: np.ndarray, e: int) -> np.ndarray:
    """Real -> signed INT4 codes (np.int32), clamped."""
    scale = 2.0 ** e
    q = np.floor(x / scale + 0.5)
    return np.clip(q, INT4_MIN, INT4_MAX).astype(np.int32)


def quantize_bias_int(b: np.ndarray, acc_exp: int, psum_width: int) -> np.ndarray:
    """Real bias -> integer at the accumulator's fixed-point (psum_width bits,
    signed). Not INT4 -- bias adds directly into the psum."""
    q = np.floor(b / (2.0 ** acc_exp) + 0.5).astype(np.int64)
    lim = 1 << (psum_width - 1)
    if np.any(q < -lim) or np.any(q > lim - 1):
        raise ValueError(f"bias overflows signed {psum_width}-bit psum: "
                         f"range [{q.min()}, {q.max()}], limit +-{lim}")
    return q.astype(np.int32)


def round_shift(x: np.ndarray, s: int) -> np.ndarray:
    """Arithmetic-shift-right by s with round-half-up. s must be >= 0.
    The bit-exact target for the Scala requant and the Verilog datapath."""
    if s < 0:
        raise ValueError(f"round_shift needs s >= 0 (got {s}); "
                         "fixed-point exponents make the requant a left shift")
    if s == 0:
        return x.astype(np.int64)
    x = x.astype(np.int64)
    return (x + (1 << (s - 1))) >> s


def requant_to_int4(psum: np.ndarray, shift: int) -> np.ndarray:
    """layer psum (integer) -> next-layer INT4 activation codes."""
    return np.clip(round_shift(psum, shift), INT4_MIN, INT4_MAX).astype(np.int32)
