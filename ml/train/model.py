"""Clasificador de casillas: CNN pequeña, 1×64×64 gris -> 13 clases."""

import torch
from torch import nn

from common.squares import CLASSES


def _block(cin: int, cout: int) -> nn.Sequential:
    return nn.Sequential(
        nn.Conv2d(cin, cout, 3, padding=1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True),
        nn.Conv2d(cout, cout, 3, padding=1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True),
    )


class SquareNet(nn.Module):
    def __init__(self, width: int = 32):
        super().__init__()
        w = width
        self.features = nn.Sequential(
            _block(1, w), nn.MaxPool2d(2),          # 32
            _block(w, 2 * w), nn.MaxPool2d(2),      # 16
            _block(2 * w, 4 * w), nn.MaxPool2d(2),  # 8
            _block(4 * w, 8 * w),
        )
        self.head = nn.Sequential(
            nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Dropout(0.2), nn.Linear(8 * w, len(CLASSES)),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: (N, 1, 64, 64) uint8 o float en 0..255."""
        x = x.float()
        # Estandarización por casilla: independiza de la luz y el contraste de
        # la foto. Va dentro del modelo para que viaje con él al exportarlo.
        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True).clamp_min(8.0)
        return self.head(self.features((x - mean) / std))
