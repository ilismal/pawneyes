"""Tesseract por línea de órdenes (solo para el prototipo en Python; en Android, Tesseract4Android)."""

import os
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


# Modelo rápido de español de Tesseract (tessdata_fast, Apache 2.0): el mismo que usa la app.
TESSDATA = Path(__file__).resolve().parents[1] / "models" / "tessdata"
# El texto enderezado mide siempre lo mismo: así no depende de cómo estime la resolución.
DPI = 300


@dataclass
class Word:
    text: str
    conf: float
    x: int
    y: int
    w: int
    h: int
    line: tuple  # (bloque, párrafo, línea) de Tesseract


def words(img: np.ndarray, psm: int = 3, lang: str = "spa", whitelist: str | None = None,
          tessdata: str | None = None) -> list[Word]:
    with tempfile.TemporaryDirectory() as d:
        path = Path(d) / "in.png"
        cv2.imwrite(str(path), img)
        cmd = ["tesseract", str(path), "stdout", "-l", lang, "--psm", str(psm),
               "-c", "tessedit_create_tsv=1", "-c", f"user_defined_dpi={DPI}"]
        if whitelist:
            cmd += ["-c", f"tessedit_char_whitelist={whitelist}"]
        env = dict(os.environ, TESSDATA_PREFIX=str(tessdata or TESSDATA))
        out = subprocess.run(cmd, capture_output=True, text=True, env=env, check=True).stdout
    result = []
    for row in out.splitlines()[1:]:
        f = row.split("\t")
        if len(f) < 12 or f[0] != "5" or not f[11].strip():
            continue
        result.append(Word(f[11], float(f[10]), int(f[6]), int(f[7]), int(f[8]), int(f[9]),
                           (int(f[2]), int(f[3]), int(f[4]))))
    return result
