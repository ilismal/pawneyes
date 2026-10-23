"""Casos de las reglas de turno y orientación, compartidos con los tests de Android.

  cd ml && uv run python -m caption.cases     # comprueba y escribe fixtures-publicos/caption.json
"""

import json
from pathlib import Path

from caption.orientation import flipped_by_coordinates
from caption.turn import Line, side_to_move

OUT = Path(__file__).resolve().parents[2] / "fixtures-publicos" / "caption.json"


def A(text, d=0.5):
    return Line(text, True, d)


def B(text, d=0.5):
    return Line(text, False, d)


TURN = [
    ([B("Juegan las negras")], "b"),
    ([B("Las blancas juegan y ganan")], "w"),
    ([A("Diagrama 12. Juegan negras y dan mate en 3")], "b"),
    ([B("Juegan las Blancas.")], "w"),
    ([B("White to move")], "w"),
    ([B("Black to play and win")], "b"),
    ([A("Mate en 5")], None),
    ([A("3.e5 c5 4.c3 Cc6 5.Cf3")], "b"),
    ([A("3.05 054.03 DC6 5.013")], "b"),
    ([A("...Ab4")], "w"),
    ([A(".2b4")], "w"),
    ([A("…Ab4")], "w"),
    ([A("19. ..., T1A ...")], "w"),
    ([A("19. -. TIA al 4")], "w"),
    ([A("19. «+. O TIA e | o")], "w"),
    ([A("12. Cf3 Cc6")], "w"),
    ([A("12. Cf3 Cc6 13. Ae2")], "b"),
    ([A("8. T2D, ...")], "b"),
    ([A("9. R3R,")], None),
    ([A('ne | 9. RIR, "')], None),
    ([A("7. TxP, T2TR | 9. R3R,")], None),
    ([A("13. Cf3, Cc6")], "w"),
    ([B("20. C5A!, R1A | ...")], "w"),
    ([B("20, C5AL, RIA 1 ...")], "w"),
    ([B("20. ..., T1A")], "b"),
    ([B("20... T1A")], "b"),
    ([A("19. ..., T1A"), B("20. C5A!")], "w"),
    ([A("5.Cf3"), B("20. C5A!")], None),
    ([A("Juegan las negras"), B("20. C5A!")], "b"),
    ([B("Juegan las negras"), B("White to move")], None),
    ([B("Las negras presionarán sobre ese peón")], None),
    ([B("Juegan las negras", 3.5)], None),
    ([A("texto", 0.2), A("más texto", 0.6), A("Juegan las negras", 1.0)], None),
    ([A("blancos, es decir, la casilla d4:", 1.5), A("3.e5 c5 4.c3 Cc6 5.Cf3", 0.3)], "b"),
    ([A("Capítulo 4", 2.0)], None),
    ([], None),
]

COORDS = [
    ({"left": "87654321", "right": "", "top": "", "bottom": "abcdefgh"}, False),
    ({"left": "12345678", "right": "", "top": "", "bottom": "hgfedcba"}, True),
    ({"left": "8765421", "right": "", "top": "ade", "bottom": ""}, False),
    ({"left": "", "right": "", "top": "", "bottom": "hgfdcb"}, True),
    ({"left": "53", "right": "", "top": "", "bottom": "e"}, None),
    ({"left": "", "right": "", "top": "", "bottom": ""}, None),
    ({"left": "36", "right": "", "top": "", "bottom": "ae"}, None),
    ({"left": "1357", "right": "", "top": "", "bottom": ""}, True),
]


def main() -> None:
    turn = []
    for lines, want in TURN:
        got = side_to_move(lines)
        assert got == want, ([l.text for l in lines], got, want)
        turn.append({"lines": [{"text": l.text, "above": l.above, "distance": l.distance}
                               for l in lines], "side": want})
    coords = []
    for found, want in COORDS:
        got = flipped_by_coordinates(found)
        assert got == want, (found, got, want)
        coords.append({"found": found, "flipped": want})
    OUT.write_text(json.dumps({"turn": turn, "coordinates": coords}, ensure_ascii=False, indent=1))
    print(f"{len(turn)} casos de turno y {len(coords)} de coordenadas en {OUT}")


if __name__ == "__main__":
    main()
