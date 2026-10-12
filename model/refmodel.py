"""Integer, tiled reference inference -- the bridge to Phase 2.

This is the bit-exact dataflow the RTL implements: 32-wide VMM passes, a
partial-sum buffer summing across input-tiles OUTSIDE the multiplier, bias at
the psum width, ReLU, arithmetic-shift requant, argmax. Phase 2's Scala model
must match this function's intermediate integers exactly on the golden images.

All inputs are integer numpy arrays (INT4 codes / psum-width bias). No floats,
no scales -- the scales are bookkeeping that lives in config.FXP and only
matters for interpreting the result, not for computing it.

Activations may carry leading batch dimensions (`(..., K)`); the tiling loops
are the hardware's and stay explicit, the batch just rides along.
"""

from __future__ import annotations

import numpy as np

from config import DIM, FXP, LAYER1, LAYER2, LayerShape
from quant import requant_to_int4


def tiled_layer(act_int: np.ndarray, w_tiles: np.ndarray, bias_int: np.ndarray,
                shape: LayerShape) -> np.ndarray:
    """One dense layer via 32x32 passes.

    act_int   : (..., K) INT4 codes
    w_tiles   : (output_tiles, input_tiles, DIM, DIM) INT4 -- w_tiles[o,k] is the
                stationary matrix for output-tile o, input-tile k, laid out so
                that  y[col] = sum_row a[row] * W[row, col].
    bias_int  : (N,) integer at the accumulator fixed-point (psum_width bits)
    returns   : (..., N) raw psum if not shape.relu (logits path);
                (..., N) INT4 codes if shape.relu (hidden activations, requantised)
    """
    batch = act_int.shape[:-1]
    a = np.zeros(batch + (shape.k_padded,), dtype=np.int64)   # zero-pad K
    a[..., : shape.k] = act_int
    psum = np.zeros(batch + (shape.n_padded,), dtype=np.int64)  # partial-sum buffer

    for o in range(shape.output_tiles):
        acc = np.zeros(batch + (DIM,), dtype=np.int64)
        for k in range(shape.input_tiles):
            a_tile = a[..., k * DIM:(k + 1) * DIM]                # (..., DIM)
            w = w_tiles[o, k].astype(np.int64)                    # (DIM, DIM)
            y = a_tile @ w                                        # (..., DIM) exact INT13
            acc += y                                              # accumulate outside VMM
        psum[..., o * DIM:(o + 1) * DIM] = acc

    psum = psum[..., : shape.n] + bias_int.astype(np.int64)

    if shape.relu:
        return requant_to_int4(np.maximum(psum, 0), FXP.requant_shift)
    return psum


def infer(image_int4: np.ndarray, params: dict) -> dict:
    """Full 784-64-10 inference. `params` as produced by export.pack_params().
    Returns intermediates for cross-checking, plus the predicted class
    (an array of classes if `image_int4` is batched)."""
    hidden = tiled_layer(image_int4, params["w1_tiles"], params["b1_int"], LAYER1)
    logits = tiled_layer(hidden, params["w2_tiles"], params["b2_int"], LAYER2)
    return {
        "hidden_int4": hidden,
        "logits": logits,
        "pred": np.argmax(logits, axis=-1),
    }
