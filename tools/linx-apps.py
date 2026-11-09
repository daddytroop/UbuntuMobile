#!/usr/bin/env python3
"""The desktop's applications, for the app's launcher. Runs inside the container.

    linx-apps.py                  one line per app: id|name|comment|kind|icon
    linx-apps.py --run ID         start the app the way its menu entry says
    linx-apps.py --tidy           clean up the desktop's menu (see tidy())

The list is what the desktop's own menu shows - every installed .desktop
entry that is meant to be seen, from Ubuntu packages and from Flathub -
minus XFCE's settings dialogs and the plumbing that comes with the image
(xterm, Openbox, the VNC server). "kind" is "app", "system" or "flatpak". "icon" is
the path of the best picture found in the icon themes (a PNG where there
is one, else an SVG), or empty.
"""

import os
import shlex
import subprocess
import sys

APP_DIRS = [
    os.path.expanduser("~/.local/share/applications"),
    "/var/lib/flatpak/exports/share/applications",
    "/usr/local/share/applications",
    "/usr/share/applications",
]
ICON_BASES = [
    os.path.expanduser("~/.local/share/icons"),
    "/var/lib/flatpak/exports/share/icons",
    "/usr/local/share/icons",
    "/usr/share/icons",
]
THEMES = ["Yaru", "hicolor", "Adwaita"]
# Biggest first: the launcher draws them at about 48dp, which is 150 pixels
# on most phones.
SIZES = ["256x256@2x", "256x256", "128x128@2x", "128x128", "scalable", "96x96",
         "64x64@2x", "64x64", "48x48@2x", "48x48", "32x32"]

# Part of the image rather than something the user chose; or a duplicate of
# another entry (XFCE's "preferred application" ones).
HIDDEN = {
    "debian-uxterm", "debian-xterm", "openbox", "tint2", "tint2conf", "x11vnc",
    "feh", "xfa", "xfe", "xfi", "xfp", "xfw", "xfce4-about", "xfce4-run",
    "xfce4-session-logout", "xfce4-mail-reader", "xfce4-web-browser",
    "xfce4-file-manager", "xfce4-terminal-emulator", "thunar-bulk-rename",
    "thunar-settings", "org.xfce.mousepad-settings", "org.gnome.Evince-previewer",
    "panel-desktop-handler", "panel-preferences", "xfce4-appfinder",
}

# Plainer names for the image's own apps, as on a phone.
NAMES = {
    "xfce4-terminal": "Terminal",
    "thunar": "Files",
    "xfce-settings-manager": "Settings",
    "org.xfce.mousepad": "Text Editor",
    "org.gnome.Evince": "Document Viewer",
    "xfce4-taskmanager": "Task Manager",
}
# Filed under System by their packages, but used every day.
NAMES_EVERYDAY = {"xfce4-terminal", "thunar"}
# Yaru's pictures for them: their packages name XFCE's own, which Yaru does
# not draw, so the menu and the dock showed XFCE's older icons.
ICONS = {
    "xfce4-terminal": "utilities-terminal",
    "thunar": "org.gnome.Nautilus",
    "xfce-settings-manager": "org.gnome.Settings",
    "org.xfce.mousepad": "org.gnome.TextEditor",
    "xfce4-taskmanager": "org.gnome.SystemMonitor",
}
# Where --tidy writes its corrections: ahead of /usr/share in the menu's
# search path, and left alone by package upgrades.
OVERRIDES = "/usr/local/share/applications"
MARK = "X-Linx-Tidy=true"


def read_entry(path):
    """The [Desktop Entry] group of a .desktop file, unlocalised keys only."""
    entry = {}
    group = None
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line.startswith("["):
                    group = line
                elif group == "[Desktop Entry]" and "=" in line and not line.startswith("#"):
                    key, value = line.split("=", 1)
                    key = key.strip()
                    if "[" not in key:
                        entry.setdefault(key, value.strip())
    except OSError:
        pass
    return entry


def entries():
    """id -> (path, entry), the first directory winning as in the menu spec."""
    found = {}
    for d in APP_DIRS:
        try:
            names = sorted(os.listdir(d))
        except OSError:
            continue
        for name in names:
            if not name.endswith(".desktop"):
                continue
            app_id = name[: -len(".desktop")]
            if app_id not in found:
                found[app_id] = (os.path.join(d, name), read_entry(os.path.join(d, name)))
    return found


def shown(app_id, e):
    if app_id in HIDDEN or app_id.startswith("python3"):
        return False
    if e.get("Type") != "Application" or not e.get("Exec") or not e.get("Name"):
        return False
    if e.get("NoDisplay", "").lower() == "true" or e.get("Hidden", "").lower() == "true":
        return False
    only = [x for x in e.get("OnlyShowIn", "").split(";") if x]
    if only and "XFCE" not in only:
        return False
    if "XFCE" in e.get("NotShowIn", "").split(";"):
        return False
    cats = e.get("Categories", "").split(";")
    # XFCE's own settings dialogs: the launcher has one Settings entry.
    if "X-XFCE-SettingsDialog" in cats or "X-XFCE-SystemSettings" in cats:
        return False
    return True


def icon_index():
    """Icon name -> best path, from one walk over the themes' app folders."""
    best = {}
    for theme in THEMES:
        for base in ICON_BASES:
            for rank, size in enumerate(SIZES):
                for sub in ("apps", "applications", "categories", "mimetypes"):
                    d = os.path.join(base, theme, size, sub)
                    try:
                        names = os.listdir(d)
                    except OSError:
                        continue
                    for n in names:
                        stem, ext = os.path.splitext(n)
                        if ext not in (".png", ".svg"):
                            continue
                        score = (THEMES.index(theme), rank)
                        if stem not in best or score < best[stem][0]:
                            best[stem] = (score, os.path.join(d, n))
    return {k: v[1] for k, v in best.items()}


def find_icon(name, index):
    if not name:
        return ""
    if name.startswith("/"):
        return name if os.path.isfile(name) else ""
    if name in index:
        return index[name]
    for ext in (".png", ".svg", ".xpm"):
        p = "/usr/share/pixmaps/" + name + ext
        if os.path.isfile(p) and ext != ".xpm":
            return p
    return ""


def real(path):
    # Flathub's exported icons are symlinks to absolute container paths,
    # which the app (reading the files from outside) could not follow.
    return os.path.realpath(path) if path else ""


def clean(text):
    return text.replace("|", "/").replace("\n", " ")


def list_apps():
    index = icon_index()
    rows = []
    for app_id, (path, e) in entries().items():
        if not shown(app_id, e):
            continue
        cats = e.get("Categories", "").split(";")
        if path.startswith("/var/lib/flatpak/"):
            kind = "flatpak"
        elif ("System" in cats or "Settings" in cats) and app_id not in NAMES_EVERYDAY:
            kind = "system"
        else:
            kind = "app"
        name = NAMES.get(app_id, e["Name"])
        rows.append((name.lower(), "|".join([
            app_id, clean(name), clean(e.get("Comment", e.get("GenericName", ""))),
            kind, real(find_icon(e.get("Icon", ""), index)),
        ])))
    for _, line in sorted(rows):
        print(line)


# XFCE's "preferred application" entries: duplicates of Firefox, Files and
# Terminal, but the panel's stock launchers point at them, so they stay
# until the dock (linx-panel.sh) has taken the launchers' place.
PANEL_LAUNCHERS = {"xfce4-web-browser", "xfce4-file-manager", "xfce4-terminal-emulator"}


def dock_installed():
    return any(os.path.exists("/usr/lib/%s/xfce4/panel/plugins/libdocklike.so" % d)
               for d in os.listdir("/usr/lib") if "linux-gnu" in d)


def hidden(app_id, e, dock=True):
    cats = e.get("Categories", "").split(";")
    settings = "X-XFCE-SettingsDialog" in cats or "X-XFCE-SystemSettings" in cats
    if app_id in PANEL_LAUNCHERS and not dock:
        return False
    return (app_id in HIDDEN or app_id.startswith("python3")
            or (settings and app_id != "xfce-settings-manager"))


def write_override(src, dest, changes):
    """A copy of src with changes made in its [Desktop Entry] group."""
    out = []
    group = None
    for line in open(src, encoding="utf-8", errors="replace"):
        text = line.rstrip("\n")
        if text.startswith("["):
            group = text
            out.append(text)
            if group == "[Desktop Entry]":
                out.extend("%s=%s" % kv for kv in changes.items())
                out.append(MARK)
            continue
        if group == "[Desktop Entry]" and "=" in text and not text.startswith("#"):
            # The key and its translations ("Name[de]") give way.
            if text.split("=", 1)[0].strip().split("[")[0] in changes:
                continue
        out.append(text)
    tmp = dest + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    os.replace(tmp, dest)


def refresh_icon_caches():
    """Rebuild an icon theme's cache that is older than a file in it.

    GTK trusts the cache: an app installed after it was built (Firefox, by
    setup) is drawn as a missing icon in the dock and the menu, or as an
    older theme's picture, until the cache is rebuilt - and in the
    container nothing else rebuilds it reliably.
    """
    for theme in ("hicolor", "Yaru"):
        d = os.path.join("/usr/share/icons", theme)
        cache = os.path.join(d, "icon-theme.cache")
        try:
            built = os.stat(cache).st_mtime
        except OSError:
            continue
        stale = False
        for root, dirs, _ in os.walk(d):
            if os.stat(root).st_mtime > built:
                stale = True
                break
        if stale:
            subprocess.call(["gtk-update-icon-cache", "-q", "-t", "-f", d],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def tidy():
    """Clean up the desktop's menu (and so the dock and the launcher).

    XFCE's settings dialogs, About, Log Out and the image's plumbing are
    hidden - the Settings app reaches all the dialogs - and the image's own
    apps get plain names and Yaru's icons. The corrections are copies in
    OVERRIDES, which the menu prefers to the packages' own, so upgrades do
    not undo them; running it again (after installing something) rewrites
    them.
    """
    refresh_icon_caches()
    os.makedirs(OVERRIDES, exist_ok=True)
    for name in os.listdir(OVERRIDES):
        path = os.path.join(OVERRIDES, name)
        try:
            if name.endswith(".desktop") and MARK in open(path, encoding="utf-8", errors="replace").read():
                os.remove(path)
        except OSError:
            pass
    yaru = os.path.isdir("/usr/share/icons/Yaru")
    dock = dock_installed()
    src_dir = "/usr/share/applications"
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith(".desktop"):
            continue
        app_id = name[: -len(".desktop")]
        dest = os.path.join(OVERRIDES, name)
        if os.path.exists(dest):
            continue  # someone else's override
        e = read_entry(os.path.join(src_dir, name))
        changes = {}
        if hidden(app_id, e, dock):
            changes["NoDisplay"] = "true"
        if app_id in NAMES:
            changes["Name"] = NAMES[app_id]
        if yaru and app_id in ICONS:
            changes["Icon"] = ICONS[app_id]
        if changes:
            write_override(os.path.join(src_dir, name), dest, changes)


def run(app_id):
    found = entries().get(app_id)
    if not found:
        sys.exit("no such application: " + app_id)
    e = found[1]
    # The field codes (%f, %U, ...) stand for files to open; there are none.
    argv = [a for a in shlex.split(e["Exec"]) if not (len(a) == 2 and a[0] == "%")]
    argv = [a.replace("%%", "%") for a in argv]
    if e.get("Terminal", "").lower() == "true":
        argv = ["xfce4-terminal", "-x"] + argv
    path = e.get("Path")
    if path and os.path.isdir(path):
        os.chdir(path)
    os.execvp(argv[0], argv)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--run":
        run(sys.argv[2])
    elif len(sys.argv) == 2 and sys.argv[1] == "--tidy":
        tidy()
    else:
        list_apps()
