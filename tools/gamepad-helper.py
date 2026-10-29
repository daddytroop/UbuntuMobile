#!/usr/bin/env python3
"""
Virtual gamepad helper for UbuntuMobile.

The Android app renders an on-screen controller and streams its state into
this process. We translate that stream into Linux input events on a virtual
uinput joystick, so games running inside the container (RetroArch, PPSSPP,
Dolphin, Steam titles, ...) see a normal gamepad.

Wire format (one line per update):

    <lx> <ly> <rx> <ry> <lt> <rt> <BUTTON,BUTTON,...>

Axes are floats in [-1, 1]; triggers are floats in [0, 1].

If /dev/uinput is not available (most unrooted Android devices do not expose
it), the helper stays idle and reports the reason instead of failing, so the
Android side can fall back to on-screen controls.
"""

import errno
import os
import struct
import sys
import time

UINPUT_PATH = "/dev/uinput"
FIFO_PATH = "/run/linx-gamepad.fifo"

# --- uinput ioctl numbers (generic, stable across kernels) ---
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_ABSBIT = 0x40045566
UI_DEV_SETUP = 0x405c5503
UI_ABS_SETUP = 0x401c5503

EV_SYN, EV_KEY, EV_ABS = 0x00, 0x01, 0x03

BTN_A, BTN_B, BTN_X, BTN_Y = 0x130, 0x131, 0x133, 0x132
BTN_TL, BTN_TR = 0x134, 0x135
BTN_SELECT, BTN_START = 0x136, 0x137
BTN_DPAD_UP, BTN_DPAD_DOWN = 0x220, 0x221
BTN_DPAD_LEFT, BTN_DPAD_RIGHT = 0x222, 0x223

ABS_X, ABS_Y, ABS_RX, ABS_RY = 0x00, 0x01, 0x03, 0x04
ABS_Z, ABS_RZ = 0x02, 0x05

BUTTON_MAP = {
    "A": BTN_A, "B": BTN_B, "X": BTN_X, "Y": BTN_Y,
    "L1": BTN_TL, "R1": BTN_TR,
    "SELECT": BTN_SELECT, "START": BTN_START,
    "DPAD_UP": BTN_DPAD_UP, "DPAD_DOWN": BTN_DPAD_DOWN,
    "DPAD_LEFT": BTN_DPAD_LEFT, "DPAD_RIGHT": BTN_DPAD_RIGHT,
}

# struct input_event: { struct timeval time; __u16 type; __u16 code; __s32 value; }
# timeval is 16 bytes on 64-bit and 8 bytes on 32-bit.
_TIMEVAL_FMT = "ll" if struct.calcsize("l") == 8 else "ll"
_EV_FMT = _TIMEVAL_FMT + "HHi"
EV_SIZE = struct.calcsize(_EV_FMT)


def ev(type_, code, value):
    return struct.pack(_EV_FMT, int(time.time()), int(time.time() % 1 * 1000000),
                       type_, code, value)


class VirtualPad:
    def __init__(self):
        self.fd = os.open(UINPUT_PATH, os.O_WRONLY | os.O_NONBLOCK)
        fcntl_ioctl(self.fd, UI_SET_EVBIT, EV_SYN)
        fcntl_ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
        fcntl_ioctl(self.fd, UI_SET_EVBIT, EV_ABS)
        for code in BUTTON_MAP.values():
            fcntl_ioctl(self.fd, UI_SET_KEYBIT, code)
        for code in (ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ):
            fcntl_ioctl(self.fd, UI_SET_ABSBIT, code)

        # struct uinput_user_dev {
        #     char name[80];
        #     struct { __u16 bustype, vendor, product, version; } id;
        #     __u32 ff_effects_max;
        #     __s32 absmax[ABS_CNT];   /* ABS_CNT is 64 */
        # }
        name = b"UbuntuMobile Virtual Gamepad\x00"
        absmax = b"".join(struct.pack("i", 0x7FFF) for _ in range(64))
        # The kernel writes the sysfs path back into this buffer, so it has
        # to be mutable - a bytes object is not.
        payload = bytearray(
            name.ljust(80, b"\x00")
            + struct.pack("HHHH", 0x03, 0x0210, 0x0001, 0x0001)
            + struct.pack("i", 0)
            + absmax
        )
        fcntl_ioctl(self.fd, UI_DEV_SETUP, payload)
        fcntl_ioctl(self.fd, UI_DEV_CREATE)
        # Let the host settle before we start emitting events.
        time.sleep(0.3)

    def write(self, data):
        os.write(self.fd, data)

    def axis(self, code, value):
        self.write(ev(EV_ABS, code, int(max(-1.0, min(1.0, value)) * 32767)))

    def trigger(self, code, value):
        self.write(ev(EV_ABS, code, int(max(0.0, min(1.0, value)) * 32767)))

    def button(self, code, pressed):
        self.write(ev(EV_KEY, code, 1 if pressed else 0))

    def sync(self):
        self.write(ev(EV_SYN, 0, 0))

    def close(self):
        try:
            fcntl_ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        os.close(self.fd)


def fcntl_ioctl(fd, request, arg=0):
    """Issue an ioctl on the uinput device.

    `arg` is the value the ioctl takes: an integer for the simple ones and
    a mutable buffer for the ones that write back into it.
    """
    import fcntl
    if isinstance(arg, int):
        return fcntl.ioctl(fd, request, arg)
    return fcntl.ioctl(fd, request, arg)


def main():
    if not os.path.exists(UINPUT_PATH):
        print("gamepad-helper: %s not available; on-screen controls only" % UINPUT_PATH)
        return 1
    try:
        pad = VirtualPad()
    except OSError as e:
        print("gamepad-helper: cannot open %s (%s)" % (UINPUT_PATH, e))
        return 1
    except Exception as e:
        print("gamepad-helper: cannot create the virtual pad (%s: %s)"
              % (type(e).__name__, e))
        return 1

    # Read the command fifo; the app writes one line per state update.
    if not os.path.exists(FIFO_PATH):
        try:
            os.mkfifo(FIFO_PATH, 0o666)
        except OSError as e:
            if e.errno != errno.EEXIST:
                print("gamepad-helper: cannot create fifo: %s" % e)
                return 1

    print("gamepad-helper: ready")
    sys.stdout.flush()

    pressed = set()
    try:
        with open(FIFO_PATH, "r") as fifo:
            for line in fifo:
                line = line.strip()
                if not line:
                    continue
                parts = line.split()
                if len(parts) < 6:
                    continue
                try:
                    lx, ly, rx, ry, lt, rt = (float(p) for p in parts[:6])
                except ValueError:
                    continue
                buttons = set(parts[6].split(",")) if len(parts) > 6 and parts[6] else set()

                pad.axis(ABS_X, lx)
                pad.axis(ABS_Y, ly)
                pad.axis(ABS_RX, rx)
                pad.axis(ABS_RY, ry)
                pad.trigger(ABS_Z, lt)
                pad.trigger(ABS_RZ, rt)

                for name, code in BUTTON_MAP.items():
                    now = name in buttons
                    was = name in pressed
                    if now != was:
                        pad.button(code, now)
                pressed = buttons
                pad.sync()
    except (OSError, KeyboardInterrupt):
        pass
    finally:
        pad.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
