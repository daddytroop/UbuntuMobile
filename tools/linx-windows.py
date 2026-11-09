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
import os
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
x11.XGetWMNormalHints.argtypes = [ctypes.c_void_p, Window, ctypes.c_void_p, ctypes.POINTER(ctypes.c_long)]
x11.XSetWMNormalHints.argtypes = [ctypes.c_void_p, Window, ctypes.c_void_p]
x11.XDisplayWidth.argtypes = [ctypes.c_void_p, ctypes.c_int]
x11.XDisplayHeight.argtypes = [ctypes.c_void_p, ctypes.c_int]
x11.XResizeWindow.argtypes = [ctypes.c_void_p, Window, ctypes.c_uint, ctypes.c_uint]
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


class SizeHints(ctypes.Structure):
    _fields_ = [
        ("flags", ctypes.c_long), ("x", ctypes.c_int), ("y", ctypes.c_int),
        ("width", ctypes.c_int), ("height", ctypes.c_int),
        ("min_width", ctypes.c_int), ("min_height", ctypes.c_int),
        ("max_width", ctypes.c_int), ("max_height", ctypes.c_int),
        ("width_inc", ctypes.c_int), ("height_inc", ctypes.c_int),
        ("min_aspect_x", ctypes.c_int), ("min_aspect_y", ctypes.c_int),
        ("max_aspect_x", ctypes.c_int), ("max_aspect_y", ctypes.c_int),
        ("base_width", ctypes.c_int), ("base_height", ctypes.c_int),
        ("win_gravity", ctypes.c_int),
    ]


P_MIN_SIZE = 1 << 4


class CursorEvent(ctypes.Structure):
    # XFixesCursorNotifyEvent
    _fields_ = [
        ("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
        ("display", ctypes.c_void_p), ("window", Window), ("subtype", ctypes.c_int),
        ("cursor_serial", ctypes.c_ulong), ("timestamp", ctypes.c_ulong), ("cursor_name", Atom),
    ]


class Event(ctypes.Union):
    # XEvent is 24 longs; the members used here are views of it.
    _fields_ = [
        ("type", ctypes.c_int), ("property", PropertyEvent), ("cursor", CursorEvent),
        ("pad", ctypes.c_long * 24),
    ]


display = x11.XOpenDisplay(None)
if not display:
    sys.exit("linx-windows: cannot open the display")
root = x11.XDefaultRootWindow(display)


def atom(name):
    return x11.XInternAtom(display, name.encode(), 0)


CLIENT_LIST = atom("_NET_CLIENT_LIST")
WINDOW_TYPE = atom("_NET_WM_WINDOW_TYPE")
TYPE_NORMAL = atom("_NET_WM_WINDOW_TYPE_NORMAL")
TYPE_DIALOG = atom("_NET_WM_WINDOW_TYPE_DIALOG")
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
    """An application's own window: an ordinary one, or a dialog that
    belongs to no other window (XFCE's Settings is one). Not a dialog of
    another window, a menu or a panel."""
    transient = Window()
    if x11.XGetTransientForHint(display, window, ctypes.byref(transient)) and transient.value:
        return False
    types = property_values(window, WINDOW_TYPE, XA_ATOM)
    return not types or types[0] in (TYPE_NORMAL, TYPE_DIALOG)


def fit_minimum(window):
    """Let a window be made as small as the screen.

    Some applications ask for a minimum size wider than a phone held
    upright - Thunar 902 pixels on a 900-pixel screen - and the window
    manager then cannot maximize them, so the title bar and its close
    button end up off the edge. The minimum is lowered to the screen's
    size; the application draws in the space it gets.
    """
    hints = SizeHints()
    supplied = ctypes.c_long()
    if not x11.XGetWMNormalHints(display, window, ctypes.byref(hints), ctypes.byref(supplied)):
        return
    if not hints.flags & P_MIN_SIZE:
        return
    # Leave room for the window's border and the panel.
    width = x11.XDisplayWidth(display, 0) - 8
    height = x11.XDisplayHeight(display, 0) - 120
    if hints.min_width <= width and hints.min_height <= height:
        return
    hints.min_width = min(hints.min_width, width)
    hints.min_height = min(hints.min_height, height)
    x11.XSetWMNormalHints(display, window, ctypes.byref(hints))
    # A window already marked maximized at its too-large size stays so;
    # sizing it to the screen first lets the maximize take.
    x11.XResizeWindow(display, window, width, height)


def maximize(window):
    fit_minimum(window)
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
x11.XGetAtomName.argtypes = [ctypes.c_void_p, Atom]
x11.XGetAtomName.restype = ctypes.c_void_p

# The pointer's cursor, for the app: it turns into a text cursor over a
# field you can type into, and the app then brings up the phone's keyboard
# after a tap. X says when it changes through the XFixes extension.
CURSOR_FILE = "/tmp/linx-cursor"
NEW_WINDOW_FILE = "/tmp/linx-new-window"
# The app in front, by its window class ("TelegramDesktop", "retroarch"),
# for the gamepad's layouts per game.
ACTIVE_APP_FILE = "/tmp/linx-active-app"
ACTIVE_WINDOW = atom("_NET_ACTIVE_WINDOW")
WM_CLASS = atom("WM_CLASS")
XA_STRING = 31


def window_class(window):
    """The class half of WM_CLASS ("instance\\0Class\\0"), or ""."""
    actual = Atom()
    fmt = ctypes.c_int()
    count = ctypes.c_ulong()
    after = ctypes.c_ulong()
    data = ctypes.c_void_p()
    status = x11.XGetWindowProperty(
        display, window, WM_CLASS, 0, 256, 0, XA_STRING,
        ctypes.byref(actual), ctypes.byref(fmt), ctypes.byref(count),
        ctypes.byref(after), ctypes.byref(data),
    )
    if status != 0 or not data.value:
        return ""
    raw = ctypes.string_at(data, count.value)
    x11.XFree(data)
    parts = raw.split(b"\0")
    name = parts[1] if len(parts) > 1 and parts[1] else parts[0]
    return name.decode("utf-8", "replace")


def active_changed():
    windows = property_values(root, ACTIVE_WINDOW, XA_WINDOW)
    name = window_class(windows[0]) if windows and windows[0] else ""
    try:
        with open(ACTIVE_APP_FILE + ".tmp", "w") as f:
            f.write(name)
        os.replace(ACTIVE_APP_FILE + ".tmp", ACTIVE_APP_FILE)
    except OSError:
        pass
CURSOR_EVENT = -1
try:
    xfixes = ctypes.cdll.LoadLibrary("libXfixes.so.3")
    xfixes.XFixesQueryExtension.argtypes = [
        ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int)]
    xfixes.XFixesSelectCursorInput.argtypes = [ctypes.c_void_p, Window, ctypes.c_ulong]
    event_base = ctypes.c_int()
    error_base = ctypes.c_int()
    if xfixes.XFixesQueryExtension(display, ctypes.byref(event_base), ctypes.byref(error_base)):
        CURSOR_EVENT = event_base.value + 1  # XFixesCursorNotify
        xfixes.XFixesSelectCursorInput(display, root, 1)  # XFixesDisplayCursorNotifyMask
except OSError:
    pass


def cursor_changed(name_atom):
    name = ""
    if name_atom:
        raw = x11.XGetAtomName(display, name_atom)
        if raw:
            name = ctypes.string_at(raw).decode("ascii", "replace")
            x11.XFree(raw)
    try:
        with open(CURSOR_FILE + ".tmp", "w") as f:
            f.write(name)
        os.replace(CURSOR_FILE + ".tmp", CURSOR_FILE)
    except OSError:
        pass
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
    if event.type == CURSOR_EVENT:
        cursor_changed(event.cursor.cursor_name)
        continue
    if event.type == PROPERTY_NOTIFY and event.property.atom == ACTIVE_WINDOW:
        active_changed()
        continue
    if event.type != PROPERTY_NOTIFY or event.property.atom != CLIENT_LIST:
        continue
    current = clients()
    new = [w for w in current if w not in known]
    known = set(current)
    if new:
        # The app shows "Opening ..." from a tap in its launcher until a
        # window appears; this file's time says one has.
        try:
            with open(NEW_WINDOW_FILE, "w") as f:
                f.write(str(len(current)))
        except OSError:
            pass
    if on_phone():
        for w in new:
            if is_app_window(w):
                maximize(w)
                pending += [(time.monotonic() + delay, w) for delay in AGAIN_AFTER]
