"""The 784-64-10 MLP, in float and QAT flavours.

One nn.Module with a `quant` switch:
  - quant=False : plain float MLP (Phase 1 baseline).
  - quant=True  : weights and activations fake-quantised to INT4 at the
                  config.FXP exponents on every forward pass (QAT).

Biases stay float here; export.py quantises them to the psum fixed-point.
The forward pass mirrors the eventual hardware order:
    x -> [fq act] -> matmul W1 -> +b1 -> ReLU -> [fq -> hidden INT4] ->
         matmul W2 -> +b2 -> logits
"""

from __future__ import annotations

import torch
import torch.nn as nn

from config import FXP, LAYER1, LAYER2
from quant import fake_quant


class MLP(nn.Module):
    def __init__(self, quant: bool = False):
        super().__init__()
        self.quant = quant
        self.fc1 = nn.Linear(LAYER1.k, LAYER1.n, bias=True)
        self.fc2 = nn.Linear(LAYER2.k, LAYER2.n, bias=True)

    def set_quant(self, on: bool) -> None:
        self.quant = on

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        if self.quant:
            x = fake_quant(x, FXP.e_act_in)
            w1 = fake_quant(self.fc1.weight, FXP.e_w1)
            h = torch.relu(nn.functional.linear(x, w1, self.fc1.bias))
            h = fake_quant(h, FXP.e_act_hidden)
            w2 = fake_quant(self.fc2.weight, FXP.e_w2)
            return nn.functional.linear(h, w2, self.fc2.bias)
        else:
            h = torch.relu(self.fc1(x))
            return self.fc2(h)
