"""Pick the best *power-of-two* per-tensor exponent by minimising quantisation
error, instead of just fitting max-abs (which leaves INT4 codes unused when a
tensor has heavy tails).

Scales stay power-of-two, so the between-layer requant is still a pure
arithmetic shift -- only the exponent chosen for each tensor changes.

    python calibrate.py checkpoints/float.pt              # search + print
    python calibrate.py checkpoints/float.pt --write      # also patch config.py FXP

Selection metric: MSE of fake-quant(x, e) vs x, over candidate exponents
e in [max_abs_e - span, max_abs_e + 1]. Finer e (more negative) shrinks the
step but clips the tails; MSE trades the two. Activation stats come from a
pass over `n_batches` of the test set.
"""

from __future__ import annotations

import argparse
import json
import math
import re

import torch

from config import INT4_MIN, INT4_MAX, ROOT
from data import test_loader
from model import MLP
from quant import round_half_up

FXP_KEYS = ("e_act_in", "e_w1", "e_act_hidden", "e_w2")


def max_abs_exponent(x: torch.Tensor) -> int:
    """Smallest integer e such that  max|x| / 2**e  <=  INT4_MAX, i.e. the
    coarsest-needed scale at which nothing clamps. Centre of the MSE search."""
    m = float(x.abs().max())
    if m == 0.0:
        return -7
    return math.ceil(math.log2(m / INT4_MAX))


def _mse_at(x: torch.Tensor, e: int) -> float:
    s = 2.0 ** e
    q = torch.clamp(round_half_up(x / s), INT4_MIN, INT4_MAX) * s
    return float(((q - x) ** 2).mean())


def best_exponent(x: torch.Tensor, span: int = 4) -> tuple[int, dict]:
    x = torch.as_tensor(x).detach().float().reshape(-1)
    e0 = max_abs_exponent(x)
    cands = list(range(e0 - span, e0 + 2))
    mses = {e: _mse_at(x, e) for e in cands}
    best = min(mses, key=mses.get)
    clip_frac = float((x.abs() > INT4_MAX * 2.0 ** best).float().mean())
    return best, {"max_abs_e": e0, "chosen": best, "clip_frac": round(clip_frac, 5),
                  "mse": {e: round(v, 8) for e, v in mses.items()}}


@torch.no_grad()
def collect_activations(model: MLP, n_batches: int = 20):
    xs, hs = [], []
    for i, (xb, _) in enumerate(test_loader()):
        if i >= n_batches:
            break
        xs.append(xb)
        hs.append(torch.relu(model.fc1(xb)))
    return torch.cat(xs), torch.cat(hs)


def search(ckpt: str) -> dict:
    m = MLP(quant=False)
    m.load_state_dict(torch.load(ckpt, map_location="cpu"))
    m.eval()
    x, h = collect_activations(m)

    e_act_in, i_in = best_exponent(x)
    e_act_hidden, i_hid = best_exponent(h)
    e_w1, i_w1 = best_exponent(m.fc1.weight)
    e_w2, i_w2 = best_exponent(m.fc2.weight)

    # requant shift must stay >= 0 (right shift). If the MSE-optimal hidden
    # exponent makes it negative, raise e_act_hidden to the minimum legal value
    # (costs a little hidden-activation resolution, keeps the no-multiplier requant).
    shift = e_act_hidden - (e_w1 + e_act_in)
    bumped = False
    if shift < 0:
        e_act_hidden = e_w1 + e_act_in
        shift = 0
        bumped = True

    return {
        "e_act_in": e_act_in, "e_w1": e_w1, "e_act_hidden": e_act_hidden, "e_w2": e_w2,
        "requant_shift": shift, "hidden_exp_bumped_for_shift": bumped,
        "detail": {"act_in": i_in, "act_hidden": i_hid, "w1": i_w1, "w2": i_w2},
    }


def patch_config(vals: dict) -> None:
    p = ROOT / "config.py"
    src = p.read_text()
    for key in FXP_KEYS:
        src = re.sub(rf"(\n    {key}: int = )-?\d+",
                     rf"\g<1>{vals[key]}", src, count=1)
    p.write_text(src)
    print(f"patched {p} FXP exponents -> "
          + ", ".join(f"{k}={vals[k]}" for k in FXP_KEYS))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    res = search(args.ckpt)
    print(json.dumps(res, indent=2))
    if args.write:
        patch_config(res)


if __name__ == "__main__":
    main()
