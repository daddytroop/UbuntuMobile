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


MIC_WANTED = "/tmp/linx-mic-wanted"


def mic_wanted():
    """Whether anything records from the phone's microphone ("phonemic")."""
    def short(what):
        out = subprocess.run(["pactl", "list", "short", what], capture_output=True, text=True, timeout=10).stdout
        return [line.split("\t") for line in out.splitlines() if line.strip()]
    try:
        index = [row[0] for row in short("sources") if len(row) > 1 and row[1] == "phonemic"]
        if not index:
            return False
        return any(len(row) > 1 and row[1] == index[0] for row in short("source-outputs"))
    except (OSError, subprocess.SubprocessError):
        return False


def watch_microphone():
    """Say whether a Linux app is listening to the phone's microphone.

    Writes 1 to /tmp/linx-mic-wanted while anything records from the
    "phonemic" source (linx-audio.sh), and 0 otherwise. The app
    (MicBridge.kt) reads the file and turns the phone's microphone on and
    off to match, so the microphone - and Android's indicator - is only on
    while an app is recording. It used to be a script of its own
    (linx-mic.sh: a proot, two shells and pactl); here it is one pactl.
    """
    def write(on):
        try:
            with open(MIC_WANTED + ".new", "w") as f:
                f.write("1\n" if on else "0\n")
            os.replace(MIC_WANTED + ".new", MIC_WANTED)
        except OSError:
            pass

    while True:
        write(mic_wanted())
        try:
            # Every recording that starts or stops is a source-output event.
            proc = subprocess.Popen(["pactl", "subscribe"], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, text=True)
            for line in proc.stdout:
                if "source-output" in line:
                    write(mic_wanted())
            proc.wait()
        except OSError:
            pass
        # The sound server went away; look again shortly.
        write(False)
        time.sleep(5)


if __name__ == "__main__":
    os.environ.setdefault("XDG_RUNTIME_DIR", "/tmp/runtime-root")
    threading.Thread(target=show_phone_notes, daemon=True).start()
    threading.Thread(target=watch_microphone, daemon=True).start()
    # dbus-monitor ends with the session; the desktop starts this again.
    while True:
        watch()
        time.sleep(5)
