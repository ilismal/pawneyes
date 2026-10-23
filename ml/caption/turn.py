"""Turno a partir del texto que rodea al diagrama (líneas leídas por OCR).

Tres pistas, por orden de prioridad:
  1. Una frase explícita en las líneas más cercanas: «Juegan las negras»,
     «Blancas juegan y ganan», «Black to move»…
  2. La última jugada impresa justo encima del diagrama: «5.Cf3» (la última es de
     las blancas: juegan negras), «19. ..., T1A» o «...Ab4» (juegan blancas).
  3. La siguiente jugada justo debajo: «20. C5A!» (blancas), «20. ..., T1A» (negras).
Si dos pistas se contradicen, no se propone nada.

El OCR lee mal las jugadas (figurines, letras sueltas), pero la estructura
«número + una o dos jugadas» sobrevive, y es lo único que se usa.
"""

import re
import unicodedata
from dataclasses import dataclass

WHITE, BLACK = "w", "b"


@dataclass
class Line:
    text: str
    above: bool       # encima (True) o debajo (False) del tablero
    distance: float   # en casillas, desde el borde del tablero


def _norm(text: str) -> str:
    t = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in t if not unicodedata.combining(c))


_SIDE = {"blancas": WHITE, "white": WHITE, "negras": BLACK, "black": BLACK}
_EXPLICIT = [
    re.compile(r"\b(?:juegan|mueven|juega|mueve|turno de)\s+(?:las\s+)?(blancas|negras)\b"),
    re.compile(r"\b(blancas|negras)\s+(?:juegan|mueven)\b"),
    re.compile(r"\b(white|black)\s+to\s+(?:move|play)\b"),
    re.compile(r"\b(white|black)\s+(?:plays|moves)\b"),
]

# Puntos suspensivos tal y como los deja el OCR: «...», «…», «. . .», ««+.», «-.».
_ELLIPSIS = r"(?:\.\s*){2,}|…|[«»+\-]+\s*\.|\.\s*[«»+\-]+"
_NUMBER = re.compile(r"(?<![\w.])(\d{1,3})\s*[.,]")
_MOVE = re.compile(r"[^\s|.,;:]{2,}")

LINES_NEAR = 2        # líneas de cada lado en las que se busca la frase explícita
MAX_DISTANCE = 3.0    # casillas: más lejos ya es texto corrido


def explicit(lines: list[Line]) -> str | None:
    near = sorted([l for l in lines if l.distance <= MAX_DISTANCE], key=lambda l: l.distance)
    sides = set()
    for above in (True, False):
        for l in [l for l in near if l.above == above][:LINES_NEAR]:
            t = _norm(l.text)
            for rx in _EXPLICIT:
                for m in rx.finditer(t):
                    sides.add(_SIDE[m.group(1)])
    return sides.pop() if len(sides) == 1 else None


def _moves_after(text: str) -> tuple[bool, int, bool]:
    """(¿empieza por puntos suspensivos?, nº de jugadas, ¿acaba en coma sin más?) tras un número.

    «9. R3R,» con la respuesta cortada (otra columna, borde de la foto) no es lo
    mismo que «8. T2D, ...», donde la respuesta se omite a propósito.
    """
    rest = text.strip()
    m = re.match(r"[\s,]*(?:" + _ELLIPSIS + r")[\s,]*", rest)
    ellipsis = m is not None
    if m:
        rest = rest[m.end():]
    parts = re.split(_ELLIPSIS, rest)  # «8. T2D, ...»: lo que va tras «...» no cuenta
    segment = parts[0].split("|")[0]
    dangling = len(parts) == 1 and re.search(r",\W*$", segment) is not None
    return ellipsis, len(_MOVE.findall(segment)), dangling


def last_move_above(line: Line) -> str | None:
    """Turno tras la última jugada de la línea de encima."""
    t = line.text.strip()
    numbers = list(_NUMBER.finditer(t))
    if not numbers:
        # «...Ab4» sin número: acaban de jugar las negras.
        if re.match(r"^\s*(?:" + _ELLIPSIS + r"|\.)\s*\S*[a-h][1-8]", t):
            return WHITE
        return None
    ellipsis, n, dangling = _moves_after(t[numbers[-1].end():])
    if ellipsis:
        return WHITE if n >= 1 else None
    if n == 1 and dangling:
        return None
    return {1: BLACK, 2: WHITE}.get(n)


def next_move_below(line: Line) -> str | None:
    """Turno de la jugada con la que empieza la línea de debajo."""
    m = _NUMBER.match(line.text.strip())
    if not m:
        return None
    ellipsis, n, _ = _moves_after(line.text.strip()[m.end():])
    if n == 0:
        return None
    return BLACK if ellipsis else WHITE


def side_to_move(lines: list[Line]) -> str | None:
    found = explicit(lines)
    if found:
        return found
    above = [l for l in lines if l.above and l.distance <= MAX_DISTANCE]
    below = [l for l in lines if not l.above and l.distance <= MAX_DISTANCE]
    hints = set()
    if above:
        hint = last_move_above(min(above, key=lambda l: l.distance))
        if hint:
            hints.add(hint)
    if below:
        hint = next_move_below(min(below, key=lambda l: l.distance))
        if hint:
            hints.add(hint)
    return hints.pop() if len(hints) == 1 else None
