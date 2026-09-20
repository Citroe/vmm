"""MNIST loading + the pixel normalisation the fixed-point convention assumes.

Uses torchvision's MNIST (downloads to config.DATA_DIR on first run -- needs
network once). If the download is blocked, drop the four IDX files into
model/data/MNIST/raw/ by hand and this still works offline.
"""

from __future__ import annotations

import torch
from torch.utils.data import DataLoader, TensorDataset
from torchvision import datasets, transforms

from config import DATA_DIR, TRAIN


def _tf() -> transforms.Compose:
    return transforms.Compose([
        transforms.ToTensor(),                                   # [0,1], (1,28,28)
        transforms.Normalize((TRAIN.pixel_mean,), (TRAIN.pixel_std,)),
        transforms.Lambda(lambda t: t.view(-1)),                 # (784,)
    ])


def _loader(train: bool, shuffle: bool) -> DataLoader:
    ds = datasets.MNIST(str(DATA_DIR), train=train, download=True, transform=_tf())
    return DataLoader(ds, batch_size=TRAIN.batch_size, shuffle=shuffle,
                      num_workers=2, drop_last=train)


def train_loader() -> DataLoader:
    return _loader(train=True, shuffle=True)


def test_loader() -> DataLoader:
    return _loader(train=False, shuffle=False)


def test_tensors() -> tuple[torch.Tensor, torch.Tensor]:
    """Whole test set as (X[N,784], y[N]) -- for calibration and golden export."""
    ds = datasets.MNIST(str(DATA_DIR), train=False, download=True, transform=_tf())
    X = torch.stack([ds[i][0] for i in range(len(ds))])
    y = torch.tensor([ds[i][1] for i in range(len(ds))])
    return X, y
