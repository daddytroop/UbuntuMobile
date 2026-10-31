#!/usr/bin/env python3
"""Generate the desktop wallpaper for the in-container XFCE session.

XFCE's stock configuration points at
``/usr/share/xfce4/backdrops/xubuntu-wallpaper.png``. The file is not part of
any package the container installs, so the desktop comes up with no
background at all. This script writes a small PNG there - a diagonal
Ubuntu-style gradient - so the desktop looks like a desktop.

It is written by hand (zlib + the PNG chunk format) so the build does not
need an image library.
"""

import struct
import sys
import zlib


def chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: str, width: int, height: int, pixel) -> None:
    """Write an 8-bit RGB PNG whose pixels come from ``pixel(x, y)``."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) for this scanline
        for x in range(width):
            r, g, b = pixel(x, y)
            raw.append(r & 0xFF)
            raw.append(g & 0xFF)
            raw.append(b & 0xFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    data = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as handle:
        handle.write(data)


def gradient(x: int, y: int, width: int, height: int):
    """A diagonal aubergine-to-purple gradient with a warm corner glow."""
    # Position along the diagonal, 0 at the top left and 1 at the bottom right.
    t = (x / width * 0.65) + (y / height * 0.35)

    # Ubuntu aubergine #2c001e -> #77216f.
    start = (0x2C, 0x00, 0x1E)
    end = (0x77, 0x21, 0x6F)
    base = [
        int(start[i] + (end[i] - start[i]) * t) for i in range(3)
    ]

    # A soft glow in the upper right, the colour of the Ubuntu orange.
    gx = x / width - 0.82
    gy = y / height - 0.16
    d = (gx * gx + gy * gy) ** 0.5
    glow = max(0.0, 1.0 - d * 3.4)
    glow = glow * glow * 90

    return (
        min(255, int(base[0] + glow * 1.00)),
        min(255, int(base[1] + glow * 0.55)),
        min(255, int(base[2] + glow * 0.20)),
    )


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "xubuntu-wallpaper.png"
    width = 1920
    height = 1080
    write_png(out, width, height, lambda x, y: gradient(x, y, width, height))
    print("wrote %s (%dx%d)" % (out, width, height))
    return 0


if __name__ == "__main__":
    sys.exit(main())
