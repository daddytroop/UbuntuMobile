#!/usr/bin/env python3
"""Check that the in-container desktop is actually drawn.

The RFB handshake alone proves very little: the server will happily answer
with a black framebuffer while the desktop is broken. This script connects
to the RFB port, asks for one frame and reports what is on it.

    check_desktop.py <port> [expected-colours]

It exits non-zero when the frame is a single flat colour, which is what a
desktop that never drew anything looks like.
"""

import socket
import struct
import sys


def recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise EOFError("the server closed the connection")
        data += chunk
    return data


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5901
    minimum = int(sys.argv[2]) if len(sys.argv) > 2 else 4

    sock = socket.create_connection(("127.0.0.1", port), timeout=15)
    sock.settimeout(20)

    greeting = recv_exact(sock, 12)
    if not greeting.startswith(b"RFB"):
        print("not an RFB server: %r" % greeting)
        return 1
    sock.sendall(b"RFB 003.008\n")

    count = recv_exact(sock, 1)[0]
    types = recv_exact(sock, count)
    if 1 not in types:
        print("the server wants authentication: %s" % list(types))
        return 1
    sock.sendall(b"\x01")
    if struct.unpack(">I", recv_exact(sock, 4))[0] != 0:
        print("the security handshake failed")
        return 1
    sock.sendall(b"\x01")  # shared session

    width, height = struct.unpack(">HH", recv_exact(sock, 4))
    pf = recv_exact(sock, 16)
    bits, depth = pf[0], pf[1]
    big_endian, true_colour = pf[2], pf[3]
    red_max, green_max, blue_max = struct.unpack(">HHH", pf[4:10])
    red_shift, green_shift, blue_shift = pf[10], pf[11], pf[12]
    name_length = struct.unpack(">I", recv_exact(sock, 4))[0]
    name = recv_exact(sock, name_length).decode("utf-8", "replace")
    print("desktop %r at %dx%d, %d bit" % (name, width, height, bits))

    # Ask for one full frame.
    sock.sendall(struct.pack(">BBHHHH", 3, 0, 0, 0, width, height))

    buckets = {}
    while True:
        message = recv_exact(sock, 1)[0]
        if message == 0:
            recv_exact(sock, 1)
            rects = struct.unpack(">H", recv_exact(sock, 2))[0]
            for _ in range(rects):
                x, y, w, h, encoding = struct.unpack(">HHHHi", recv_exact(sock, 12))
                if encoding != 0:
                    print("unexpected encoding %d (only raw is decoded here)" % encoding)
                    return 1
                raw = recv_exact(sock, w * h * (bits // 8))
                step = bits // 8
                for i in range(0, len(raw), step):
                    if step == 4:
                        value = struct.unpack(">I" if big_endian else "<I", raw[i:i + 4])[0]
                    else:
                        value = int.from_bytes(
                            raw[i:i + step], "big" if big_endian else "little")
                    r = ((value >> red_shift) & red_max) * 255 // max(red_max, 1)
                    g = ((value >> green_shift) & green_max) * 255 // max(green_max, 1)
                    b = ((value >> blue_shift) & blue_max) * 255 // max(blue_max, 1)
                    key = (r // 32, g // 32, b // 32)
                    buckets[key] = buckets.get(key, 0) + 1
            break
        elif message == 1:
            recv_exact(sock, 6)
        elif message == 2:
            pass
        elif message == 3:
            recv_exact(sock, struct.unpack(">I", recv_exact(sock, 4))[0])
        else:
            print("unexpected server message %d" % message)
            return 1

    total = sum(buckets.values())
    print("%d distinct colours in %d pixels" % (len(buckets), total))
    for (r, g, b), count in sorted(buckets.items(), key=lambda kv: -kv[1])[:5]:
        print("   rgb(%3d,%3d,%3d) %6.2f%%" % (r * 32, g * 32, b * 32, 100.0 * count / total))

    if len(buckets) < minimum:
        print("FAIL: the frame has fewer than %d distinct colours - "
              "the desktop is not drawn" % minimum)
        return 1

    # An optional colour that has to be on the screen. It is how the test
    # checks that an application really drew something: xlogo paints a
    # white box, and the wallpaper gradient never has pure white in it.
    if len(sys.argv) > 3:
        want = tuple(int(part) for part in sys.argv[3].split(","))
        key = (want[0] // 32, want[1] // 32, want[2] // 32)
        if key not in buckets:
            print("FAIL: the colour rgb%s is not on the screen" % (want,))
            return 1
        print("the colour rgb%s is on the screen" % (want,))

    print("OK: the desktop is drawn")
    return 0


if __name__ == "__main__":
    sys.exit(main())
