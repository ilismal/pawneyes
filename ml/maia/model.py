"""Maia 1 (https://github.com/CSSLab/maia-chess) reconstruida en PyTorch desde los pesos de lc0.

Arquitectura (NETWORK_SE_WITH_HEADFORMAT, POLICY_CONVOLUTION): convolución de
entrada 112→64, 6 bloques residuales con SE y cabeza de política convolucional
(64→64→80 planos de 8×8, traducidos a las 1858 jugadas de lc0). La cabeza de
valor no se usa: para jugar basta la política.

Convención de tableros (la de lc0): plano[fila][columna] con fila 0 = primera
fila; si juegan las negras, la posición se ve girada (filas invertidas y colores
intercambiados), así que la red siempre "ve" al bando que mueve abajo.
"""

import gzip
import sys
from pathlib import Path

import numpy as np
import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).parent))
import net_pb2  # noqa: E402
from policy_index_lc0 import policy_index  # noqa: E402

EPS = 1e-5


def _decode(layer) -> np.ndarray:
    """Capa de pesos de lc0 (LINEAR16) -> float32."""
    u = np.frombuffer(layer.params, dtype=np.uint16).astype(np.float32)
    return (layer.min_val + (layer.max_val - layer.min_val) * u / 65535.0).astype(np.float32)


def _conv_map() -> np.ndarray:
    """Para cada una de las 1858 jugadas, su posición en la salida 80×8×8 aplanada."""
    import lc0_az_policy_map  # usa policy_index
    m = lc0_az_policy_map.make_map()  # (5120, 1858), con un 1 por jugada
    return np.argmax(m, axis=0).astype(np.int64)


class ConvBN(nn.Module):
    def __init__(self, cin: int, cout: int, k: int):
        super().__init__()
        self.conv = nn.Conv2d(cin, cout, k, padding=k // 2, bias=False)
        self.bn = nn.BatchNorm2d(cout, eps=EPS)

    def forward(self, x):
        return self.bn(self.conv(x))

    def load(self, block) -> None:
        cout, cin, k = self.conv.out_channels, self.conv.in_channels, self.conv.kernel_size[0]
        self.conv.weight.data = torch.from_numpy(_decode(block.weights).reshape(cout, cin, k, k))
        mean = _decode(block.bn_means)
        var = _decode(block.bn_stddivs)  # lc0 guarda aquí la varianza
        if block.biases.params:
            mean = mean - _decode(block.biases)  # sesgo de la convolución, plegado en la media
        gamma = _decode(block.bn_gammas) if block.bn_gammas.params else np.ones(cout, np.float32)
        beta = _decode(block.bn_betas) if block.bn_betas.params else np.zeros(cout, np.float32)
        self.bn.running_mean.data = torch.from_numpy(mean)
        self.bn.running_var.data = torch.from_numpy(var)
        self.bn.weight.data = torch.from_numpy(gamma)
        self.bn.bias.data = torch.from_numpy(beta)


class Residual(nn.Module):
    def __init__(self, ch: int, se_ch: int):
        super().__init__()
        self.c1, self.c2 = ConvBN(ch, ch, 3), ConvBN(ch, ch, 3)
        self.fc1, self.fc2 = nn.Linear(ch, se_ch), nn.Linear(se_ch, 2 * ch)

    def forward(self, x):
        y = self.c2(torch.relu(self.c1(x)))
        s = torch.relu(self.fc1(y.mean(dim=(2, 3))))
        z, b = self.fc2(s).chunk(2, dim=1)
        y = torch.sigmoid(z)[:, :, None, None] * y + b[:, :, None, None]
        return torch.relu(y + x)

    def load(self, r) -> None:
        self.c1.load(r.conv1)
        self.c2.load(r.conv2)
        ch, se_ch = self.fc1.in_features, self.fc1.out_features
        self.fc1.weight.data = torch.from_numpy(_decode(r.se.w1).reshape(se_ch, ch))
        self.fc1.bias.data = torch.from_numpy(_decode(r.se.b1))
        self.fc2.weight.data = torch.from_numpy(_decode(r.se.w2).reshape(2 * ch, se_ch))
        self.fc2.bias.data = torch.from_numpy(_decode(r.se.b2))


class MaiaNet(nn.Module):
    """Entrada (N, 112, 8, 8) float; salida (N, 1858) logits de política."""

    def __init__(self, blocks: int = 6, ch: int = 64, se_ch: int = 32):
        super().__init__()
        self.input = ConvBN(112, ch, 3)
        self.tower = nn.Sequential(*[Residual(ch, se_ch) for _ in range(blocks)])
        self.policy1 = ConvBN(ch, ch, 3)
        self.policy = nn.Conv2d(ch, 80, 3, padding=1)
        self.register_buffer("gather", torch.from_numpy(_conv_map()))

    def forward(self, x):
        x = self.tower(torch.relu(self.input(x)))
        p = self.policy(torch.relu(self.policy1(x)))
        return p.flatten(1)[:, self.gather]

    @classmethod
    def from_lc0(cls, path: Path) -> "MaiaNet":
        net = net_pb2.Net()
        net.ParseFromString(gzip.open(path).read())
        w = net.weights
        ch = len(_decode(w.input.bn_means))
        se_ch = len(_decode(w.residual[0].se.b1))
        model = cls(len(w.residual), ch, se_ch)
        model.input.load(w.input)
        for block, r in zip(model.tower, w.residual):
            block.load(r)
        model.policy1.load(w.policy1)
        model.policy.weight.data = torch.from_numpy(_decode(w.policy.weights).reshape(80, ch, 3, 3))
        model.policy.bias.data = torch.from_numpy(_decode(w.policy.biases))
        return model.eval()


POLICY_INDEX = policy_index
