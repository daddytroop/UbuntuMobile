#!/usr/bin/env python3
"""Test the virtual gamepad helper.

The helper talks to /dev/uinput with ioctls, which needs a real device.
Instead of one, the ioctl layer is replaced with a recorder, so the test can
check that the device is set up the way the kernel expects:

  * every capability is announced before the device is created,
  * the UI_DEV_SETUP payload is a mutable buffer of the right size,
  * UI_DEV_CREATE is issued last, and UI_DEV_DESTROY on the way out.

The helper used to crash on startup because it called fcntl_ioctl() with
two arguments where three were required, and because it passed an
immutable buffer to UI_DEV_SETUP.
"""

import importlib.util
import os
import struct
import sys
import types

HERE = os.path.dirname(os.path.abspath(__file__))
HELPER = os.path.join(HERE, "gamepad-helper.py")

failures = []


def check(condition, message):
    if condition:
        print("  ok    %s" % message)
    else:
        print("  FAIL  %s" % message)
        failures.append(message)


def load_helper():
    spec = importlib.util.spec_from_file_location("gamepad_helper", HELPER)
    module = importlib.util.module_from_spec(spec)
    # The helper must not do anything at import time.
    spec.loader.exec_module(module)
    return module


def main():
    if not os.path.isfile(HELPER):
        print("the helper is missing: %s" % HELPER)
        return 1

    helper = load_helper()
    print("=== the helper loads ===")

    calls = []

    def fake_ioctl(fd, request, arg=0, *rest):
        calls.append((request, arg))
        return 0

    # Replace the ioctl layer with a recorder.
    import fcntl
    real_ioctl = fcntl.ioctl
    fcntl.ioctl = fake_ioctl
    try:
        helper.fcntl_ioctl = lambda fd, request, arg=0: fake_ioctl(fd, request, arg)

        # Replace os.open so nothing is really opened.
        real_open = os.open
        os.open = lambda path, flags, *a: 99
        try:
            pad = helper.VirtualPad()
        finally:
            os.open = real_open
    finally:
        fcntl.ioctl = real_ioctl

    print("=== the virtual pad is set up correctly ===")
    requests = [c[0] for c in calls]

    check(helper.UI_SET_EVBIT in requests, "the event capabilities are announced")
    check(helper.UI_SET_KEYBIT in requests, "the button capabilities are announced")
    check(helper.UI_SET_ABSBIT in requests, "the axis capabilities are announced")
    check(helper.UI_DEV_SETUP in requests, "the device is described")
    check(helper.UI_DEV_CREATE in requests, "the device is created")

    # The device must be created after everything else.
    if helper.UI_DEV_CREATE in requests:
        last = len(requests) - 1 - requests[::-1].index(helper.UI_DEV_CREATE)
        check(
            last == len(requests) - 1,
            "the device is created last (UI_DEV_CREATE is at %d of %d)"
            % (last + 1, len(requests)),
        )

    setup = [c for c in calls if c[0] == helper.UI_DEV_SETUP]
    check(len(setup) == 1, "the device is described exactly once")
    if setup:
        payload = setup[0][1]
        check(
            isinstance(payload, bytearray),
            "the description is a mutable buffer (the kernel writes back into it)",
        )
        # name[80] + id[4 x u16] + ff_effects_max[u32] + absmax[64 x s32]
        check(len(payload) == 80 + 8 + 4 + 64 * 4,
              "the description has the size the kernel expects (%d bytes)" % len(payload))
        check(
            payload[:80].rstrip(b"\x00") == b"UbuntuMobile Virtual Gamepad",
            "the device is named",
        )

    evbits = [c for c in calls if c[0] == helper.UI_SET_EVBIT]
    check(len(evbits) == 3, "three event types are announced (syn, key, abs)")

    keybits = [c for c in calls if c[0] == helper.UI_SET_KEYBIT]
    check(
        len(keybits) == len(helper.BUTTON_MAP),
        "every button is announced (%d of %d)" % (len(keybits), len(helper.BUTTON_MAP)),
    )

    absbits = [c for c in calls if c[0] == helper.UI_SET_ABSBIT]
    check(len(absbits) == 6, "the six axes are announced (%d)" % len(absbits))

    print("=== closing the pad ===")
    calls.clear()
    real_close = os.close
    os.close = lambda fd: 0
    try:
        pad.close()
    finally:
        os.close = real_close
    check(
        any(c[0] == helper.UI_DEV_DESTROY for c in calls),
        "the device is destroyed on close",
    )

    print()
    if failures:
        print("GAMEPAD_TEST_FAILED (%d)" % len(failures))
        return 1
    print("GAMEPAD_TEST_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
