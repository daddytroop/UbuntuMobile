#!/usr/bin/env python3
"""Put text on the desktop's clipboard, in any language. Runs in the container.

    linx-clip.py < text      (UTF-8)

The VNC clipboard from the phone to Linux is Latin-1, so the app hands
other text here instead. This owns the CLIPBOARD selection and answers the
apps that paste (UTF8_STRING, and Latin-1 STRING for old ones) until
something else is copied. It talks to X through ctypes, like
linx-windows.py, so it needs nothing beyond Python and libX11.
"""

import ctypes
import os
import sys

x11 = ctypes.cdll.LoadLibrary("libX11.so.6")
Window = Atom = ctypes.c_ulong
Time = ctypes.c_ulong

x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XDefaultRootWindow.restype = Window
x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x11.XCreateSimpleWindow.restype = Window
x11.XCreateSimpleWindow.argtypes = [ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_int,
                                    ctypes.c_uint, ctypes.c_uint, ctypes.c_uint,
                                    ctypes.c_ulong, ctypes.c_ulong]
x11.XInternAtom.restype = Atom
x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
x11.XSetSelectionOwner.argtypes = [ctypes.c_void_p, Atom, Window, Time]
x11.XGetSelectionOwner.restype = Window
x11.XGetSelectionOwner.argtypes = [ctypes.c_void_p, Atom]
x11.XNextEvent.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
x11.XChangeProperty.argtypes = [ctypes.c_void_p, Window, Atom, Atom, ctypes.c_int, ctypes.c_int,
                                ctypes.c_void_p, ctypes.c_int]
x11.XSendEvent.argtypes = [ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_long, ctypes.c_void_p]
x11.XFlush.argtypes = [ctypes.c_void_p]

SELECTION_CLEAR = 29
SELECTION_REQUEST = 30
SELECTION_NOTIFY = 31
PROP_MODE_REPLACE = 0


class XSelectionRequestEvent(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
                ("display", ctypes.c_void_p), ("owner", Window), ("requestor", Window),
                ("selection", Atom), ("target", Atom), ("property", Atom), ("time", Time)]


class XSelectionEvent(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
                ("display", ctypes.c_void_p), ("requestor", Window), ("selection", Atom),
                ("target", Atom), ("property", Atom), ("time", Time)]


class XEvent(ctypes.Union):
    _fields_ = [("type", ctypes.c_int), ("xselectionrequest", XSelectionRequestEvent),
                ("xselection", XSelectionEvent), ("pad", ctypes.c_long * 24)]


def main():
    text = sys.stdin.buffer.read().decode("utf-8", "replace")
    dpy = x11.XOpenDisplay(os.environ.get("DISPLAY", ":99").encode())
    if not dpy:
        sys.exit("cannot open the display")
    atom = lambda name: x11.XInternAtom(dpy, name.encode(), 0)
    clipboard, targets, utf8 = atom("CLIPBOARD"), atom("TARGETS"), atom("UTF8_STRING")
    string, text_atom, plain = atom("STRING"), atom("TEXT"), atom("text/plain;charset=utf-8")
    atom_type = atom("ATOM")
    win = x11.XCreateSimpleWindow(dpy, x11.XDefaultRootWindow(dpy), 0, 0, 1, 1, 0, 0, 0)
    x11.XSetSelectionOwner(dpy, clipboard, win, 0)
    if x11.XGetSelectionOwner(dpy, clipboard) != win:
        sys.exit("could not take the clipboard")
    x11.XFlush(dpy)

    data_utf8 = text.encode("utf-8")
    data_latin1 = text.encode("latin-1", "replace")
    offered = (Atom * 5)(targets, utf8, plain, text_atom, string)
    event = XEvent()
    while True:
        x11.XNextEvent(dpy, ctypes.byref(event))
        if event.type == SELECTION_CLEAR:
            return  # something else was copied
        if event.type != SELECTION_REQUEST:
            continue
        req = event.xselectionrequest
        prop = req.property or req.target
        if req.target == targets:
            x11.XChangeProperty(dpy, req.requestor, prop, atom_type, 32, PROP_MODE_REPLACE,
                                offered, len(offered))
        elif req.target in (utf8, plain, text_atom):
            buf = ctypes.create_string_buffer(data_utf8, len(data_utf8))
            x11.XChangeProperty(dpy, req.requestor, prop, utf8 if req.target == text_atom else req.target,
                                8, PROP_MODE_REPLACE, buf, len(data_utf8))
        elif req.target == string:
            buf = ctypes.create_string_buffer(data_latin1, len(data_latin1))
            x11.XChangeProperty(dpy, req.requestor, prop, string, 8, PROP_MODE_REPLACE, buf, len(data_latin1))
        else:
            prop = 0  # a kind this does not have
        reply = XEvent()
        reply.xselection = XSelectionEvent(type=SELECTION_NOTIFY, send_event=1, display=dpy,
                                           requestor=req.requestor, selection=req.selection,
                                           target=req.target, property=prop, time=req.time)
        x11.XSendEvent(dpy, req.requestor, 0, 0, ctypes.byref(reply))
        x11.XFlush(dpy)


if __name__ == "__main__":
    main()
