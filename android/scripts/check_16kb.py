"""Falla si alguna librería nativa de la APK no está alineada a páginas de 16 KB.

  python3 android/scripts/check_16kb.py app/build/outputs/apk/release/app-release.apk

Los móviles con páginas de 16 KB no cargan (ni ejecutan) ELF con segmentos
alineados a 4 KB. El lint de Android no revisa libstockfish.so porque es un
ejecutable, así que se comprueba aquí todo lo que acaba en .so.
"""

import struct
import sys
import zipfile

PAGE = 16 * 1024
PT_LOAD = 1


def load_alignments(elf: bytes) -> list[int]:
    if elf[:4] != b"\x7fELF" or elf[4] != 2:  # solo ELF de 64 bits
        return []
    phoff, = struct.unpack_from("<Q", elf, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if struct.unpack_from("<I", elf, off)[0] == PT_LOAD:
            out.append(struct.unpack_from("<Q", elf, off + 48)[0])
    return out


def main() -> int:
    bad = 0
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for name in sorted(n for n in apk.namelist() if n.endswith(".so")):
            aligns = load_alignments(apk.read(name))
            ok = bool(aligns) and min(aligns) >= PAGE
            bad += not ok
            print(f"{'ok ' if ok else 'MAL'} {min(aligns, default=0):>6}  {name}")
    if bad:
        print(f"{bad} librería(s) sin alinear a 16 KB")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
