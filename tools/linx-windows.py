#!/usr/bin/env python3
"""Open application windows maximized while the desktop is on the phone.

A desktop application opens at the size it would have on a monitor, which
is wider than a phone held upright, so it hangs off the screen. While the
UI scale is 2 (the phone; see display-scale.sh) every new application
window is maximized, the way phone apps fill the screen. On a monitor
(scale 1) windows open at their own size.

    linx-windows.py          watch for new windows (runs with the desktop)
    linx-windows.py --all    maximize the windows already open, for when
                             the desktop moves back to the phone

It talks to X through ctypes, so it needs nothing beyond Python and libX11,
and it waits for X events rather than polling.
"""
import ctypes
import select
import time
import sys

SCALE_FILE = "/tmp/linx-ui-scale"

x11 = ctypes.cdll.LoadLibrary("libX11.so.6")
Window = Atom = ctypes.c_ulong

x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XDefaultRootWindow.restype = Window
x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x11.XInternAtom.restype = Atom
x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
x11.XSelectInput.argtypes = [ctypes.c_void_p, Window, ctypes.c_long]
x11.XNextEvent.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
x11.XGetWindowProperty.argtypes = [
    ctypes.c_void_p, Window, Atom, ctypes.c_long, ctypes.c_long, ctypes.c_int, Atom,
    ctypes.POINTER(Atom), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_ulong),
    ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_void_p),
]
x11.XGetTransientForHint.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(Window)]
x11.XSendEvent.argtypes = [ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_long, ctypes.c_void_p]
x11.XFree.argtypes = [ctypes.c_void_p]
x11.XFlush.argtypes = [ctypes.c_void_p]

PROPERTY_NOTIFY = 28
CLIENT_MESSAGE = 33
PROPERTY_CHANGE_MASK = 1 << 22
SUBSTRUCTURE_NOTIFY_MASK = 1 << 19
SUBSTRUCTURE_REDIRECT_MASK = 1 << 20
XA_ATOM = 4
XA_WINDOW = 33


class PropertyEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
        ("display", ctypes.c_void_p), ("window", Window), ("atom", Atom),
        ("time", ctypes.c_ulong), ("state", ctypes.c_int),
    ]


class ClientMessageEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
        ("display", ctypes.c_void_p), ("window", Window), ("message_type", Atom),
        ("format", ctypes.c_int), ("data", ctypes.c_long * 5),
    ]


class Event(ctypes.Union):
    # XEvent is 24 longs; the members used here are views of it.
    _fields_ = [("type", ctypes.c_int), ("property", PropertyEvent), ("pad", ctypes.c_long * 24)]


display = x11.XOpenDisplay(None)
if not display:
    sys.exit("linx-windows: cannot open the display")
root = x11.XDefaultRootWindow(display)


def atom(name):
    return x11.XInternAtom(display, name.encode(), 0)


CLIENT_LIST = atom("_NET_CLIENT_LIST")
WINDOW_TYPE = atom("_NET_WM_WINDOW_TYPE")
TYPE_NORMAL = atom("_NET_WM_WINDOW_TYPE_NORMAL")
WM_STATE = atom("_NET_WM_STATE")
MAXIMIZED_VERT = atom("_NET_WM_STATE_MAXIMIZED_VERT")
MAXIMIZED_HORZ = atom("_NET_WM_STATE_MAXIMIZED_HORZ")


def property_values(window, prop, kind):
    actual = Atom()
    fmt = ctypes.c_int()
    count = ctypes.c_ulong()
    after = ctypes.c_ulong()
    data = ctypes.c_void_p()
    status = x11.XGetWindowProperty(
        display, window, prop, 0, 1024, 0, kind,
        ctypes.byref(actual), ctypes.byref(fmt), ctypes.byref(count),
        ctypes.byref(after), ctypes.byref(data),
    )
    if status != 0 or not data.value:
        return []
    # Format-32 properties come back as an array of longs.
    values = list(ctypes.cast(data, ctypes.POINTER(ctypes.c_ulong))[: count.value])
    x11.XFree(data)
    return values


def clients():
    return property_values(root, CLIENT_LIST, XA_WINDOW)


def is_app_window(window):
    """An ordinary application window: not a dialog, a menu or a panel."""
    transient = Window()
    if x11.XGetTransientForHint(display, window, ctypes.byref(transient)) and transient.value:
        return False
    types = property_values(window, WINDOW_TYPE, XA_ATOM)
    return not types or types[0] == TYPE_NORMAL


def maximize(window):
    # The EWMH way: ask the window manager, which keeps the panel visible.
    event = ClientMessageEvent()
    event.type = CLIENT_MESSAGE
    event.window = window
    event.message_type = WM_STATE
    event.format = 32
    event.data[0] = 1  # _NET_WM_STATE_ADD
    event.data[1] = MAXIMIZED_VERT
    event.data[2] = MAXIMIZED_HORZ
    event.data[3] = 1  # from an application
    x11.XSendEvent(
        display, root, 0, SUBSTRUCTURE_REDIRECT_MASK | SUBSTRUCTURE_NOTIFY_MASK,
        ctypes.byref(event),
    )
    x11.XFlush(display)


def on_phone():
    try:
        with open(SCALE_FILE) as f:
            return f.read().strip() == "2"
    except OSError:
        return True


if "--all" in sys.argv:
    for w in clients():
        if is_app_window(w):
            maximize(w)
    sys.exit(0)

x11.XConnectionNumber.argtypes = [ctypes.c_void_p]
x11.XPending.argtypes = [ctypes.c_void_p]

# Some applications (Firefox) size their window again just after it
# appears, which undoes the maximize; so new windows are maximized once
# more a little later.
AGAIN_AFTER = (0.8, 2.5)

known = set(clients())
x11.XSelectInput(display, root, PROPERTY_CHANGE_MASK)
event = Event()
fd = x11.XConnectionNumber(display)
pending = []  # (when, window)
while True:
    now = time.monotonic()
    due = [w for when, w in pending if when <= now]
    pending = [(when, w) for when, w in pending if when > now]
    for w in due:
        if w in known and on_phone():
            maximize(w)
    if not x11.XPending(display):
        timeout = min((when for when, _ in pending), default=now + 3600) - now
        select.select([fd], [], [], max(timeout, 0))
        continue
    x11.XNextEvent(display, ctypes.byref(event))
    if event.type != PROPERTY_NOTIFY or event.property.atom != CLIENT_LIST:
        continue
    current = clients()
    new = [w for w in current if w not in known]
    known = set(current)
    if on_phone():
        for w in new:
            if is_app_window(w):
                maximize(w)
                pending += [(time.monotonic() + delay, w) for delay in AGAIN_AFTER]
