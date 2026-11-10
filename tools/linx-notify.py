#!/usr/bin/env python3
"""Pass Linux apps' notifications to the phone. Runs in the desktop session.

Apps notify through org.freedesktop.Notifications on the session bus;
xfce4-notifyd shows them on the desktop. This watches the same calls with
dbus-monitor and sends each one, as a JSON line, to the app's abstract unix
socket (its name is in /etc/linx/notify-socket), which posts it as an
Android notification while the app is in the background - so a Telegram
message reaches the phone's notification shade like any other.

dbus-monitor prints a Notify call as its arguments, one per line indented
three spaces: app name, replaces id, icon, summary, body, actions, hints,
timeout. Strings are printed raw between quotes and may span lines.

The other way, the app drops the phone's notifications (and battery
warnings) as JSON files in /tmp/linx-phone-notes while the desktop is in
sight; they are shown with notify-send, their app name ending in
"(phone)", which is how the watch above knows not to send them back.
"""

import json
import os
import socket
import subprocess
import threading
import time

SOCKET_FILE = "/etc/linx/notify-socket"
PHONE_NOTES = "/tmp/linx-phone-notes"
PHONE_SUFFIX = " (phone)"
MATCH = "type='method_call',interface='org.freedesktop.Notifications',member='Notify'"
ARG_TYPES = ("string ", "uint32 ", "int32 ", "array [", "boolean ", "byte ", "int64 ", "variant ")


def socket_name():
    try:
        with open(SOCKET_FILE) as f:
            return f.read().strip()
    except OSError:
        return ""


def send(note):
    name = socket_name()
    if not name:
        return
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.settimeout(3)
        s.connect("\0" + name)
        s.sendall((json.dumps(note) + "\n").encode())
    except OSError:
        pass
    finally:
        s.close()


def top_level_args(lines):
    """The call's arguments, each the text of its (possibly several) lines."""
    args = []
    for line in lines:
        if line.startswith("   ") and not line.startswith("    ") and line[3:].startswith(ARG_TYPES):
            args.append(line[3:])
        elif args:
            args[-1] += "\n" + line
    return args


def string_value(arg):
    if not arg.startswith('string "'):
        return ""
    value = arg[len('string "'):]
    return value[:-1] if value.endswith('"') else value


def parse(lines):
    args = top_level_args(lines)
    if len(args) < 5:
        return None
    return {
        "app": string_value(args[0]),
        "icon": string_value(args[2]),
        "title": string_value(args[3]),
        "body": string_value(args[4]),
    }


def watch():
    proc = subprocess.Popen(
        ["dbus-monitor", "--session", MATCH],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace",
    )
    call = None
    for raw in proc.stdout:
        line = raw.rstrip("\n")
        if line.startswith(("method call ", "signal ", "method return ", "error ")):
            call = [] if (line.startswith("method call ") and "member=Notify" in line) else None
        elif call is not None:
            call.append(line)
            # Notify has eight arguments; the last, the timeout, ends it.
            if line.startswith("   int32 ") and len(top_level_args(call)) >= 8:
                note = parse(call)
                if note and (note["title"] or note["body"]) and not note["app"].endswith(PHONE_SUFFIX):
                    send(note)
                call = None
    proc.wait()


def show_phone_notes():
    """Show what the app left in PHONE_NOTES, oldest first."""
    while True:
        try:
            names = sorted(os.listdir(PHONE_NOTES))
        except OSError:
            names = []
        for name in names:
            path = os.path.join(PHONE_NOTES, name)
            try:
                with open(path) as f:
                    note = json.load(f)
            except (OSError, ValueError):
                note = None
            try:
                os.remove(path)
            except OSError:
                pass
            if not note:
                continue
            cmd = ["notify-send", "-a", (note.get("app") or "Phone") + PHONE_SUFFIX,
                   "-i", note.get("icon") or "phone"]
            if note.get("urgent"):
                cmd += ["-u", "critical"]
            cmd += [note.get("title") or "", note.get("body") or ""]
            try:
                subprocess.run(cmd, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except (OSError, subprocess.SubprocessError):
                pass
        time.sleep(1)


if __name__ == "__main__":
    threading.Thread(target=show_phone_notes, daemon=True).start()
    # dbus-monitor ends with the session; the desktop starts this again.
    while True:
        watch()
        time.sleep(5)
