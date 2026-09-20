"""Integer, tiled reference inference -- the bridge to Phase 2.

This is the bit-exact dataflow the RTL implements: 32-wide VMM passes, a
partial-sum buffer summing across input-tiles OUTSIDE the multiplier, bias at
the psum width, ReLU, arithmetic-shift requant, argmax. Phase 2's Scala model
must match this function's intermediate integers exactly on the golden images.

All inputs are integer numpy arrays (INT4 codes / psum-width bias). No floats,
no scales -- the scales are bookkeeping that lives in config.FXP and only
matters for interpreting the result, not for computing it.
"""

from __future__ import annotations

import numpy as np

from config import DIM, INT4_MIN, INT4_MAX, LAYER1, LAYER2
from quant import requant_to_int4


def _pad_k(v: np.ndarray, k_padded: int) -> np.ndarray:
    """zero-pad a length-K activation vector up to k_padded."""
    out = np.zeros(k_padded, dtype=np.int64)
    out[: v.shape[0]] = v
    return out


def tiled_layer(act_int: np.ndarray, w_tiles: np.ndarray, bias_int: np.ndarray,
                shape, relu: bool) -> np.ndarray:
    """One dense layer via 32x32 passes.

    act_int   : (K,) INT4 codes
    w_tiles   : (output_tiles, input_tiles, DIM, DIM) INT4 -- w_tiles[o,k] is the
                stationary matrix for output-tile o, input-tile k, laid out so
                that  y[col] = sum_row a[row] * W[row, col].
    bias_int  : (N,) integer at the accumulator fixed-point (psum_width bits)
    returns   : (N,) if not relu -> raw psum (logits path);
                (N,) INT4 codes   if relu (hidden activations, requantised)
    """
    a = _pad_k(act_int.astype(np.int64), shape.k_padded)
    psum = np.zeros(shape.n_padded, dtype=np.int64)      # partial-sum buffer

    for o in range(shape.output_tiles):
        acc = np.zeros(DIM, dtype=np.int64)
        for k in range(shape.input_tiles):
            a_tile = a[k * DIM:(k + 1) * DIM]                     # (DIM,)
            w = w_tiles[o, k].astype(np.int64)                    # (DIM, DIM)
            y = a_tile @ w                                        # (DIM,) exact INT13
            acc += y                                              # accumulate outside VMM
        psum[o * DIM:(o + 1) * DIM] = acc

    psum = psum[: shape.n] + bias_int.astype(np.int64)

    if relu:
        psum = np.maximum(psum, 0)
        from config import FXP
        return requant_to_int4(psum, FXP.requant_shift)
    return psum


def infer(image_int4: np.ndarray, params: dict) -> dict:
    """Full 784-64-10 inference. `params` as produced by export.pack_params().
    Returns intermediates for cross-checking, plus the predicted class."""
    hidden = tiled_layer(image_int4, params["w1_tiles"], params["b1_int"],
                         LAYER1, relu=True)
    logits = tiled_layer(hidden, params["w2_tiles"], params["b2_int"],
                         LAYER2, relu=False)
    return {
        "hidden_int4": hidden,
        "logits": logits,
        "pred": int(np.argmax(logits)),
    }
