# Mobuntu

[![Build APK](https://github.com/daddytroop/UbuntuMobile/actions/workflows/build-apk.yml/badge.svg)](https://github.com/daddytroop/UbuntuMobile/actions/workflows/build-apk.yml)
**A complete Ubuntu 26.04 Linux system that runs on an Android phone.**

Light when you want it, a full desktop when you dock it. (The app was called
UbuntuMobile; the repository and the Android package id keep that name so
updates install over older versions.)

One APK gives you a real Linux userland — bash, apt, GCC, Python, Git — and a
full-screen XFCE desktop with Firefox and an app store for Ubuntu's desktop
applications, driven from a native Android shell.

```
dist/
  app-arm64-release.apk    317 MB   <- most phones (install this one)
  app-armhf-release.apk    286 MB   <- 32-bit ARM
  app-amd64-release.apk    331 MB   <- Chromebooks / emulators
```

Install with `adb install dist/app-arm64-release.apk`, or copy the APK to the
phone and open it (enable "install from unknown sources").

---

## How it works

```
┌──────────────────────────────────────────────┐
│  Android app (Kotlin + Jetpack Compose)       │
│  • Ubuntu 26.04 desktop shell (GNOME 50 feel) │
│  • Terminal backed by a real PTY               │
│  • Ubuntu Software — install apps with apt    │
│  • Linux Display — X11 apps, streamed in      │
│  • Virtual gamepad (Xbox layout)              │
└───────────────┬──────────────────────────────┘
                │  proot (ptrace-based chroot)
┌───────────────▼──────────────────────────────┐
│  Ubuntu 26.04 "Resolute Raccoon" rootfs       │
│  glibc 2.43 · bash 5.3 · flatpak 1.16.6       │
│  build-essential · python 3.14 · git · curl   │
│  Flathub remote (GPG key embedded, offline)   │
│  Xvnc (X server + RFB export in one process)  │
└──────────────────────────────────────────────┘
```

The **shell is native Android**, not a GNOME session running inside the
container. That is the key to it feeling fast: the desktop, app grid, dash
and store are real Compose UI at 120 fps, while the container provides the
actual Linux work. Running a full GNOME under `proot` would be unusably slow
because every syscall is intercepted.

The rootfs is a genuine Ubuntu 26.04 image built from Canonical's
`ubuntu-base` and provisioned with ~330 packages.

---

## What is preinstalled

| Area | Contents |
|---|---|
| Shell & core | bash, coreutils, findutils, grep, sed, tar, gzip/zstd/xz, jq, ripgrep |
| Build | `build-essential` (gcc/g++/make), cmake, ninja, pkg-config, python3-dev |
| Languages | Python 3.14 + pip + venv, git, gnupg |
| Network | curl, wget, openssh-client, iproute2, dnsutils, socat |
| Flatpak | flatpak 1.16.6, bubblewrap, appstream, **Flathub remote with GPG key** |
| Media / GL | mesa, libGL/libEGL/libGLES, pulseaudio-utils, alsa-utils |
| Fonts | DejaVu, Noto core, Noto colour emoji |

Flathub is added from a bundled `flathub.flatpakrepo`, so the remote is
registered **offline** and every app can be installed immediately.

---

## Features

### Ubuntu 26.04 desktop shell
Native Compose implementation of the GNOME 50 / Yaru look: top bar with
Activities, centred clock and status indicators, an app grid with search, a
dash, and a home panel showing live guest information (kernel, architecture,
Flatpak version, Flathub remotes, disk usage).

### Terminal
A real POSIX PTY (implemented in NDK/C++, because Android's bionic has no
`forkpty`), so interactive programs, colours, readline and job control all
behave normally. A small ANSI parser handles SGR colour, erase and cursor
sequences.

### Ubuntu Software
A native store with two sources, switched at the top:

- **Ubuntu**: a featured page (Firefox, LibreOffice, GIMP, Inkscape, VLC,
  Geany, ...) and a search of the Ubuntu archive. Backed by
  `apt-install.sh`, which fetches apt's package lists the first time (the
  image ships without them) and installs with `apt-get`.
- **Flathub**: a featured page (Thunderbird, Telegram, Signal, VS Code,
  Krita, Blender, OBS Studio, mpv, Flatseal) and a search of the whole
  Flathub catalogue. The first install turns Flatpak on (`linx-flatpak.sh`)
  and brings the app's runtime, a few hundred MB.

Installed apps from either appear in the desktop's Applications menu.

### Flatpak under proot
Flatpak runs every app in a bubblewrap sandbox, which needs kernel
namespaces proot cannot provide, so out of the box Flatpak apps install and
then cannot start. `tools/linx-bwrap` takes `/usr/bin/bwrap`'s place
(through `dpkg-divert`, so upgrades keep it): it reads bwrap's arguments
and works out the same file system - an empty root plus the runtime at
`/usr`, the app at `/app`, and Flatpak's other bind mounts, directories,
symlinks and files - then asks the app, over an abstract unix socket, to
start it (`SandboxSpawner.kt`). The app starts a fresh proot next to the
container's, with the container paths turned into the phone's, so a Flatpak
app runs as fast as anything else in the container. What it cannot give is
the sandbox itself: Flatpak apps run with the same access as the rest of
Linux.

Flatpak's D-Bus filter is handed file descriptors that cannot cross to the
app, so it alone runs in a proot nested inside the container's (slow, but
it mostly waits). Only Flatpak gets a sandbox: anyone else calling bwrap
(GTK's glycin image loaders) gets the answer bwrap gives without namespace
support, as before.

### Onboarding
The first time the app starts it asks, Ubuntu-installer style, how the
Linux should be (`OnboardingScreen.kt`), while Ubuntu unpacks behind it:

- **How much**: Light (the desktop, terminal and files), Standard (adds
  Firefox and a document viewer) or Full (adds LibreOffice, GIMP and VLC),
  with the download each means.
- **Look**: light or dark, one of Yaru's eight accent colours, a
  wallpaper, and the desktop's size on the phone (a monitor always gets a
  full-size desktop).
- **Apps**: the starting set, ticked by the choice above, to add to or
  take from.
- **For developers**: popular open-source tools from GitHub that Ubuntu
  packages - Git, GitHub CLI, Neovim, lazygit, btop, fastfetch, ripgrep,
  fzf, bat, zoxide, tmux - each on its own or all at once.
- **App stores**: Ubuntu's archive always; Flathub on or off, with a few of
  its apps to start with.

The choices go to `/etc/linx/setup.conf`, and once the desktop is up
`linx-setup.sh` installs them in the background - sound and Ubuntu's look
always, then the rest - while a card at the top of the desktop shows the
step, what apt or Flatpak is doing and a progress bar (`SetupBanner.kt`,
from apt's `APT::Status-Fd` lines). If it fails, tapping the card retries. It
copies the file to `setup.done` when everything is in, so an interrupted
setup carries on next time. Settings' "Set up" goes through it again: the
look changes at once, and only what is missing is installed.

### Firefox
Firefox is the desktop's browser, installed by setup unless left out. Ubuntu's own `firefox` package only
installs the snap, which cannot run here, so it comes from Mozilla's apt
repository (`install-firefox.sh`), pinned above Ubuntu's. Mozilla builds it
for amd64 and arm64; on 32-bit ARM the app installs GNOME Web instead.

### Sound
PulseAudio in the container plays into a virtual sound card ("Phone") and
streams it as raw PCM on 127.0.0.1:4713 (`linx-audio.sh`). The app plays
that stream through Android (`AudioBridge.kt`), so Firefox, VLC and the rest
come out of the phone's speaker, headphones or Bluetooth. Silence is not
passed on, and the Android track pauses when nothing plays.

### Clipboard
Copy on the phone, paste in Linux, and the other way round: the RFB
clipboard messages carry it, and the app syncs with Android's clipboard when
it changes and when the app comes back to the front. The basic RFB clipboard
is Latin-1, so other characters arrive as "?".

### Phone folders
With storage permission (asked the first time; Settings can ask again),
Downloads, Documents, Pictures, Music and Videos in the Linux home folder
are the phone's own (`linx-folders.sh`), so a file saved in Firefox shows
up in the phone's Downloads, and a photo is one click away in GIMP.

### Look
Ubuntu's Yaru theme and icons, light or dark in the chosen accent colour,
and the Ubuntu font (`linx-look.sh`), a dark bottom panel, and window
borders drawn for the app by `make-xfwm4-theme.py` to match - a flat title
bar with round minimise and maximise buttons and a close button in the
accent colour - at 1x for a monitor and 2x for the phone.

### Notifications on the phone
Linux apps notify through `org.freedesktop.Notifications`, and xfce4-notifyd
shows those notifications on the desktop. `linx-notify.py` watches the same
calls with `dbus-monitor` and sends each one to the app over an abstract
unix socket (`<package>.notify`). While the app is in the background, it
posts each as an Android notification with the app's own icon
(`NotificationBridge.kt`). The notification channel is created the first time
the desktop is on screen, because Android only asks an app built for older
versions whether it may notify while that app is in the foreground.

### Linux apps on the home screen
Long-press an app in "All apps" and choose **Add to home screen**. The
shortcut carries the app's id and opens it on the desktop. If the app is
not running, the shortcut starts it first and waits until the desktop has
finished starting, so the app does not race the window manager.

### Snapshots
Settings → Snapshots saves the whole system to a `.tar.zst` file wherever you
choose, through Android's file picker, and restores one the same way
(`linx-snapshot.sh`). tar runs in the container and is streamed straight to
the file, so there is no copy in between. On the emulator it wrote about
12 MB/s. A save that fails deletes its partial file. Restoring replaces the
files the snapshot has and keeps files you added since.

### The app in your colours
The accent colour chosen at setup colours the app as well as Linux: the
floating button, menus, Settings, the store and the system bars
(`UbuntuPalette.accent`, with dark backgrounds derived from it). Settings
has a **Look** card for light or dark, the accent and the wallpaper. A
change there applies to the app and the desktop at once, with nothing
reinstalled.

### Typing
The phone's keyboard comes up by itself after a tap on a text field. X has no
"a field has focus" signal, but over a text field the pointer becomes a text
cursor. `linx-windows.py` follows cursor changes through XFixes and writes the
cursor's name to `/tmp/linx-cursor`, and after a tap the app checks for
"text". This needs a cursor theme, because the core X cursors have no names;
`linx-look.sh` sets Yaru's.

### Wallpapers
Six wallpapers (Aubergine, Aurora, Dunes, Ridges, Graphite, Dawn), drawn by
the app with Android's canvas (`Wallpapers.kt`) rather than shipped as
pictures: the same code draws onboarding's previews and the 2048-pixel
files it writes to `/usr/local/share/linx/wallpapers`, where `linx-look.sh`
sets the chosen one for xfdesktop (and feh under Openbox).

### Finding apps
Every installed app is in two places. The app's own launcher ("All apps")
reads the desktop's menu entries through `linx-apps.py` - Ubuntu packages
and Flathub apps alike, with their own icons (PNG, or SVG drawn with
AndroidSVG) - and is read afresh each time it opens, so what setup or the
store just installed is there. The panel's app button is Whisker Menu
(`linx-panel.sh`, installed by setup): a search field, favourites and every
app in one list, instead of XFCE's categories menu that hid apps two levels
down. Next to it is a dock (`xfce4-docklike-plugin`) in place of XFCE's
launchers and window list: pinned apps and open windows as one row of
icons, with a dot under the running ones, as in Ubuntu's dock.

`linx-apps.py --tidy` keeps the menu clean. It hides XFCE's settings dialogs
(the Settings app reaches them all), About, Log Out and the image's plumbing,
and gives the image's own apps plain names and Yaru's icons: Terminal, Files,
Text Editor, Settings. It does this with copies in
`/usr/local/share/applications`, which the menu prefers to the packages' own
entries and which upgrades leave alone. It also rebuilds icon caches that are
older than their themes; otherwise an app installed later (Firefox, by setup)
shows as a missing icon. It runs with the look and after every apt install.

When setup fails, the card at the top says why (the step's last error, from
`/tmp/linx-setup-error`) and offers **Share log**, which hands the last lines
of every step's log to mail or chat as text.

### The app around the desktop
The desktop fills the screen; the status and navigation bars hide until
swiped in. A small floating button at the right edge (drag it up or down)
opens a side menu with everything that is the app's rather than Linux's: the
app grid, Firefox, Terminal, Files, Software, the keyboard, the
gamepad, docking, reconnecting and **Settings**. Settings has the desktop
size (Compact, Standard - the default, sized for a 6-inch phone - and
Large; the desktop resizes as soon as it is changed), full screen on or off,
the browser, and version details.

### Linux Display — running real X11 apps
This is the piece that turns the container into a usable desktop.

Inside the container, TigerVNC's **Xvnc** is the X server and the RFB
server in one, with an XFCE desktop: xfwm4 (Openbox as the fallback), a
single Plasma-style panel along the bottom (`tools/xfce4-panel.xml`),
xfdesktop and Thunar, in Noto Sans. On the phone, application windows open
maximized (`linx-windows.py`); on a monitor they open at their own size.
The app talks to Xvnc with a hand-written RFB client (`VncClient.kt`),
which asks for Raw pixels (on the phone's own loopback that is cheaper than
having the server compress them) and copies them in bulk, also decodes
CopyRect and Hextile, and takes part in resizing (DesktopSize and
ExtendedDesktopSize): the app asks Xvnc for a new screen size and the
desktop rearranges itself.

Two properties make this work without any extra APK:

- **proot shares the host network namespace**, so the container's
  `127.0.0.1` *is* the phone's loopback. The app connects straight to
  `127.0.0.1:5901` — no port forwarding, no WebSocket proxy.
- The RFB server binds to the loopback interface only, and the app speaks
  the protocol directly rather than going through a browser, so there is no
  JavaScript or noVNC payload in the APK.

If Xvnc does not come up, the container falls back to **Xvfb + x11vnc**,
which polls the framebuffer. That stack cannot change size (Xvfb's RandR
only knows its starting size), so with it the desktop keeps the shape it
started with. display-start.sh picks whichever works.

Pixels are decoded according to the pixel format the server announces in
ServerInit rather than a format the client imposes, so the colours are
right whatever the server prefers (32bpp BGRX, 16bpp RGB565, big-endian
or an unusual channel order). The decoder is covered by a hermetic test
that runs a fake RFB server and asserts every pixel matches.

Touch is translated into pointer events and the on-screen keyboard supplies
key events, so ordinary X applications (xterm, xeyes, GTK apps) can be
driven from the phone. A USB or Bluetooth mouse works as a real mouse
(hover, three buttons, wheel) and a physical keyboard as a PC keyboard
(function keys, Super, the numeric keypad, the layout's own characters).

### Virtual gamepad
An Xbox-layout on-screen controller (dual sticks, D-pad, ABXY, triggers,
shoulders, Select and Start) drawn over the desktop, with the controls at the
edges like a handheld's. Only the controls take touches, so the game stays
visible and touchable in between. Docked, the game is on the monitor and the
pad fills the phone's screen instead.

The pencil in the corner opens the editor (`GamepadSettings.kt`). You can drag
any part to a new place and set how see-through and how big the pad is. The
style is classic colours, mono, or the accent colour. You also choose what
Linux sees:
- **Controller:** a virtual uinput joystick, through `gamepad-helper.py`.
- **Keyboard keys:** these work with every game. You pick each button's key,
  the left stick and D-pad act as the arrow keys or WASD, and the right stick
  moves the mouse.

When Linux cannot have a controller, keyboard keys is the default.

Layouts can be kept per game. `linx-windows.py` writes the window class of
the app in front to `/tmp/linx-active-app`. The editor's "Just <app>" choice
saves the layout under that name, and the pad switches to it whenever that
app is in front.

> **Note:** `/dev/uinput` is not exposed to unprivileged apps on most Android
> devices, so the Linux-side virtual pad only works on rooted devices or
> ROMs that permit it. The app detects this and says so instead of pretending
> it works. The on-screen controls themselves are always usable.

### Rotation
The desktop follows the phone. Rotating it resizes the X screen to the new
shape (it is never restarted, and open windows stay open): in portrait the
app's actions sit in a bar above the desktop, in landscape they move to a
rail at the side and the status and navigation bars hide until swiped in.
The shell, gamepad and terminal re-layout too.

### Docking: a desktop on a monitor
Connect the phone to a monitor (a USB-C dock, or a USB-C to HDMI or
DisplayPort cable) and the desktop moves to the monitor at its own
resolution, laid out like a normal desktop rather than a phone's. The phone
becomes the trackpad: slide to move, tap to click, two-finger tap to
right-click, two fingers to scroll, hold then slide to drag, plus left and
right buttons. A mouse and keyboard plugged into the dock drive the desktop
directly (the trackpad captures the mouse; tap the chip to let it go).
"Show on the phone" in the menu brings the desktop back; unplugging does
too.

How it fits together: the monitor is an Android presentation display, and
the app shows the desktop there in a `Presentation`. The X screen is
resized to the monitor's size (capped at about 1080p of pixels, since every
pixel is streamed), and the UI scale switches from 2 (phone) to 1 (monitor)
while everything runs: the scale is served through XSETTINGS by
xfsettingsd, which running GTK applications follow (`display-scale.sh`).
Qt applications only read their scale when they start.

The phone has to offer the monitor as a presentation display, which phones
with DisplayPort over USB-C do. Phones that only mirror (no video over
USB-C) cannot be docked this way. On a tablet, or in a large window
(Samsung DeX, Android's desktop windowing) the desktop is laid out at scale
1 as well.

---

## Project layout

```
UbuntuMobile/
├── app/
│   ├── src/main/
│   │   ├── java/com/ubuntumobile/
│   │   │   ├── MainActivity.kt          navigation shell
│   │   │   ├── linux/                    container engine
│   │   │   │   ├── LinuxPaths.kt         on-device layout
│   │   │   │   ├── LinuxRuntime.kt       proot invocation
│   │   │   │   ├── ProotInstaller.kt     proot + loader extraction
│   │   │   │   ├── RootfsInstaller.kt    first-run provisioning
│   │   │   │   ├── SandboxSpawner.kt     starts Flatpak apps for linx-bwrap
│   │   │   │   ├── TarExtractor.kt       streaming tar (unit tested)
│   │   │   │   └── Pty.kt                PTY session
│   │   │   ├── ui/                       onboarding, store, settings, terminal
│   │   │   └── gamepad/                  virtual controller
│   │   ├── cpp/linx_pty.cpp              PTY implementation (NDK)
│   │   ├── assets/proot/<abi>/           proot, loader, libtalloc, libandroid-shmem
│   │   └── res/                          theme + launcher icon
│   ├── src/arm64/assets/rootfs/          Ubuntu 26.04 arm64 image
│   ├── src/armhf/assets/rootfs/          Ubuntu 26.04 armhf image
│   └── src/amd64/assets/rootfs/          Ubuntu 26.04 amd64 image
├── tools/                                rootfs build, and the scripts run in the container
└── dist/                                 release APKs
```

---

## Building

### Requirements
- JDK 21, Android SDK (platform 36, build-tools 36, NDK 28, CMake 3.22)
- Gradle 8.14+ (a wrapper is included, so `./gradlew` works)

### Commands

```bash
# debug build for every ABI
./gradlew :app:assembleDebug

# signed release build
./gradlew :app:assembleRelease

# unit tests (unpacks the real rootfs and checks it is intact)
./gradlew :app:testArm64DebugUnitTest
```

Build output goes to `%LOCALAPPDATA%\UbuntuMobile\build\app\outputs\apk`
on Windows and `~/.cache/UbuntuMobile/build/app/outputs/apk` elsewhere
(configurable with `-Pubuntumobile.buildDir=...`). The project lives inside
OneDrive, which conflicts with Gradle's file locking, so outputs are
redirected to local disk by default.

### One-command bootstrap

`tools/bootstrap.sh` fetches the proot runtime, rebuilds the Ubuntu rootfs
for all three architectures, stages them into the app and builds the APKs.
It needs WSL2 with root access (the rootfs build extracts Ubuntu's `.deb`
packages, which is root-only for the cross-architecture package indexes).

```bash
tools/bootstrap.sh                # everything
tools/bootstrap.sh --skip-rootfs   # reuse an existing rootfs
```

### Rebuilding the rootfs

`tools/build_rootfs.sh` rebuilds the Ubuntu image from Canonical's
`ubuntu-base` inside WSL. It downloads the `.deb`s for the target
architecture, extracts them, merges the dpkg database, generates the CA
bundle and packages the result.

```bash
tools/build_rootfs.sh arm64      # or armhf / amd64
tools/stage_rootfs.sh            # copy into the app's flavor assets
```

### Testing the container

`tools/integration_test.sh` runs the container for real on the build
machine: it takes a rootfs, runs the **exact command line the app builds**
against it with a real proot and checks that the guest userspace works.

```bash
tools/build_proot_host.sh         # build proot (with its loader) for the host
tools/integration_test.sh amd64   # run the container end to end
```

This is the only check that catches the two classes of problem this app
keeps hitting:

* **Options proot does not accept.** The Termux build of proot rejects
  anything it does not know (`unknown option '--'`), while the upstream
  proot a developer builds on a desktop happily accepts it. The same
  difference is checked statically by `ProotOptionsTest`, which reads the
  option names out of the three proot binaries that ship in the APK.
* **Guest programs that depend on something proot changes.** Ubuntu 26.04
  ships coreutils twice, and the Rust (uutils) build works out which
  utility to run from `AT_EXECFN` — which proot sets to the path of its
  own loader helper, so every command dies with `unknown program
  'loader'`. The rootfs builder therefore points the standard names at the
  GNU build, which ignores `argv[0]` and works under proot untouched.

### Publishing a release

Every change that should reach a phone gets its own tag, so the builds are
easy to tell apart and there is always a "latest" download:

``bash
tools/release.sh v0.1.2            # builds, publishes and tags
tools/release.sh v0.1.2 notes.md   # with your own release notes
``

The script bumps ersionCode/ersionName, builds the release APKs,
publishes them as a GitHub release and pushes the tag. The repository is
**private**, so download the APKs from a browser that is signed in to
GitHub as `daddytroop`.

Because the repository is private, GitHub answers unauthenticated download
requests with a 404 rather than a 403. If a download link appears broken,
check that you are signed in.

### Continuous integration

`.github/workflows/build-apk.yml` runs on every push: it rebuilds the
rootfs, runs the unit tests, builds the APK and uploads it as an artifact
(`ubuntu-mobile-apk`).

---

## Design notes and hard-won lessons

**Android's seccomp filter returns ENOSYS for `faccessat2`.** Apps run under
a filter that the adb shell does not have, and proot passes the call
through. `dpkg-divert` checks files with `faccessat2`, so when the app runs it,
it fails with "Function not implemented"; run from `adb shell` it works. That
is how Flathub setup failed on phones but passed every emulator test, where
`bwrap` had been diverted long before. `linx-flatpak.sh` now writes the
diversion by hand when `dpkg-divert` fails. To test anything setup does, run
it from the app.

**NoDisplay hides an entry from XFCE's Settings app too.** XFCE's Settings
lists its dialogs from the same menu entries, so hiding them from the app
menu left it empty. Whisker Menu adds every entry it finds whatever the menu
file says. The way out: `/usr/local/bin/xfce4-settings-manager`, which
`--tidy` writes, starts Settings without `/usr/local/share` in
`XDG_DATA_DIRS`, so it sees the packages' own entries.

**A minimum size wider than the screen stops a maximize.** Thunar asks for
902 pixels on a 900-pixel phone, and xfwm4 then left its close button off
the edge. `linx-windows.py` lowers such minimums to the screen size and
resizes the window before maximizing it. It also maximizes standalone
dialogs (XFCE's Settings is one).

**A program that wants a session bus and finds none starts one.** The first
`flatpak install` auto-launched a `dbus-daemon`. It outlived the install, and
proot waits for every process it traced, so the app's shell never returned and
the progress card stayed on its last step. `/etc/profile.d/linx-session.sh`
(written by `display-start.sh`) points every login shell at the desktop's bus,
or at `disabled:` before there is one.

**proot inside proot works, but is slow.** A nested proot needs
`PROOT_NO_SECCOMP` (with seccomp, some system calls escape the inner
proot's path translation and the "sandbox" shows the container's root), and
then every system call stops in two tracers: about thirteen times slower.
That is why Flatpak apps are started by the app, in a proot of their own.

**proot's fake hard links point at the phone's paths.** `--link2symlink`
turns each hard link (all of Flatpak's files are hard links into its
repository) into a symlink to an absolute path under `/data/data/...`, so a
proot that starts a Flatpak app must bind `/data` too.

**`os.path.normpath("//usr")` is `//usr`.** POSIX allows two leading
slashes to mean something special, so Python keeps them. Joining "/" onto
an absolute path and normalising it made every bind mount miss.

**bwrap's `--args` can carry empty arguments.** `--setenv NAME ""` is
three NUL-terminated strings, one of them empty; dropping empty strings
shifted every argument after it.

These are the things that will bite you if you change the build pipeline.

**Ubuntu 26.04 is a merged-`/usr` system.** `/bin -> usr/bin`, `/lib -> usr/lib`,
`/sbin -> usr/sbin`, `/lib64 -> usr/lib64`. Tar-based extraction can replace
those symlinks with real directories, which leaves the guest without
`/bin/bash`. The build restores them explicitly and the verification step
fails the build if they are missing.

**Never re-serialise dpkg control stanzas.** Parsing a stanza into fields and
writing it back corrupts multi-line `Description` fields, producing
`field name 'Debian' must be followed by colon`. That breaks `apt` and
`flatpak` entirely. The build merges *raw* stanzas taken from
`dpkg-deb --ctrl-tarfile` and only rewrites the `Status` line in place.

**The CA bundle has to be generated by hand.** `ca-certificates` normally
builds `/etc/ssl/certs/ca-certificates.crt` in its post-install script,
which never runs when you only extract packages. Without it, every TLS
connection fails and `flatpak remote-add` refuses to load Flathub.

**`flatpak remote-add --from` is a boolean flag.** The syntax is
`flatpak remote-add --from NAME /path/to/x.flatpakrepo`. Passing the path
before the name makes Flatpak treat the *name* as the file to load.

**Use `flatpak remote-ls`, not `flatpak search`.** `search` needs an appstream
catalogue that is not present in a minimal container and fails with
`Could not parse XML data`.

**The Flathub remote lives in the system scope.** Installs therefore must not
pass `--user`, or Flatpak reports `No remote refs found for 'flathub'`.

**Termux's proot has its loader path baked in.** It points at
`/data/data/com.termux/files/usr/libexec/proot/loader`, which does not exist
in our APK, so every `execve` fails with *"the loader was not found"*. The app
sets `PROOT_LOADER` / `PROOT_LOADER_32` to the copies it extracts.

**aapt2 rewrites `.gz` assets.** A `.gz` suffix makes aapt2 decompress the
asset and strip the extension from the packaged name. The rootfs is therefore
shipped as `*.rootfs` and marked `noCompress`.

**GNU tar long names need explicit handling.** A `L` entry carries a path
that is too long for the 100-byte header field; skipping the entry without
consuming its data misaligns the whole stream and every later file is
garbage. `TarExtractor` reads those entries and the unit test caught exactly
this bug.

**RFB details that are easy to get wrong.** The `PIXEL_FORMAT` in
`ServerInit` is 16 bytes and has **no length prefix** (the 4-byte length
only exists in the `SetPixelFormat` message), and its fields are mixed
sizes: bits-per-pixel/depth/flags are 1 byte, colour maxima are 2 bytes,
shifts are 1 byte. In `Hextile` (RFC 6143 7.7.4) the tile is first filled
with the background, the sub-rectangle count is a **byte of its own**, a
`SubrectsColoured` tile puts a pixel before each sub-rectangle, and each
sub-rectangle is two bytes of nibbles (x/y, then width-1/height-1).
`ServerCutText` has three bytes of padding before its length, and a
`KeyEvent` is eight bytes (two of padding). x11vnc sends `ServerCutText`
whenever the clipboard changes, and drops a client whose key events are a
byte short - each of these got the stream out of step on the phone.

**Never touch a socket on Android's main thread.** Connecting, and writing
input events, throw `NetworkOnMainThreadException`. The display connects
on an IO dispatcher and every outgoing message goes through one sender
thread, which also keeps them in order.

**proot needs `--link2symlink` and `--sysvipc` on Android.** Android forbids
hard links in app storage, and the X server takes its lock file with
`link(2)` (`Linking lock file ... Permission denied`); dpkg needs them too.
Android also has no System V IPC, so without the emulation MIT-SHM does not
work. Note that the emulation is per proot process: a program in a second
proot cannot share memory with the X server and falls back to sending
pixels over the socket, which works.

**GTK loads images through glycin, and glycin's sandbox cannot work under
proot.** Its bwrap availability test passes under proot's fake root, then
every real image load fails - no icons, no wallpaper, and `xfce4-panel`
aborts on the first missing icon. A stand-in `bwrap` first on the desktop's
`PATH` (`tools/nosandbox-bwrap`) answers the way bwrap does without
namespace support, and glycin then loads images unsandboxed. Flatpak keeps
the real one through `FLATPAK_BWRAP`.

**D-Bus needs a machine id**, which only systemd creates, and the guest
needs `TZ`, because the rootfs has no `/etc/localtime`. `display-start.sh`
writes the former and the app passes the phone's time zone.

**The guest scripts ship in the APK too.** `display-start.sh`,
`desktop-start.sh`, `tint2rc`, `gamepad-helper.py` and `nosandbox-bwrap`
are bundled as assets and copied into the rootfs before every display
start, so a fix to one of them reaches existing installs with the next APK.
`.gitattributes` keeps them LF on a Windows checkout - bash cannot run a
CRLF script.

**Two names for the same binary.** Ubuntu 26.04's
`tigervnc-standalone-server` installs `/usr/bin/Xtigervnc`; the
`/usr/bin/Xvnc` name is an alternatives symlink that only the package's
maintainer script creates — which never runs when you just extract `.deb`
files. The build recreates the alias.

**Ubuntu 26.04 ships coreutils twice.** `rust-coreutils` installs a single
multicall binary at `/usr/bin/coreutils` with hard links under
`/usr/lib/cargo/bin/coreutils/` and the familiar names as symlinks;
`gnu-coreutils` installs the classic binaries as `/usr/bin/gnu<name>`.
The Rust build works out which utility to run from **`AT_EXECFN`** — and
proot sets `AT_EXECFN` to the path of its own loader helper — so every
coreutils command in the container dies with
`coreutils: unknown program 'loader'`. The GNU binaries ignore `argv[0]`
and work under proot untouched, so the builder points the standard names
at them and drops the Rust multicall binary.

**proot kills everything it started when it exits.** proot registers
`atexit(kill_all_tracees)`, so a background job cannot outlive the shell
that launched it. The X server therefore needs a proot process of its own
that stays alive for as long as the display is wanted — the app starts
one with `LinuxRuntime.startPersistent()`.

**proot has no end-of-options marker.** Upstream proot accepts a bare `--`
before the command; the Termux build does not and refuses to start with
`unknown option '--'`. Anything the app passes to proot has to be in the
option list of the binary that actually ships.

**The container needs a resolver.** `ubuntu-base` ships an empty
`/etc/resolv.conf`, so the guest can reach the internet but cannot resolve
a name — and Flatpak needs names to reach Flathub. The app writes the
phone's own DNS servers into the rootfs when the container starts.

**`x11vnc` refuses to start when `WAYLAND_DISPLAY` is set.** The display
helper unsets it before launching the server.

---

**proot must use seccomp.** The app used to run proot with
`PROOT_NO_SECCOMP=1`, which stops every process on every system call
instead of only on the ones proot rewrites. Timed on the emulator, starting
programs was 2.7 times and walking files 3.8 times slower that way, and the
whole desktop felt it: a terminal took over 20 seconds to open, and opens
in about 3 now.

**xfwm4 works under Xvnc, but starts slowly.** It never took over under the
old Xvfb display, which is why Openbox was used. Under Xvnc it does, after
loading its theme's images - each through glycin, GTK's image loader, which
starts loader processes - which can take twenty seconds on a phone. Killing
it for being slow leaves the screen claimed and Openbox refuses to start,
so desktop-start.sh waits for as long as xfwm4 is alive.

**Firefox needs a /dev/shm, and no seals.** Android has no `/dev/shm`, so
Firefox's parent process crashes as soon as it starts; the app binds a
folder of its own there in every proot process. Then every tab died with
"Shared memory PlatformHandle is not safe to map": Gecko seals the shared
memory it hands a tab, and the seal does not survive proot.
`MOZ_SHM_NO_SEALS=1` (set by `/usr/local/bin/firefox`, with the sandbox
switches) skips it. DroidDeck and proot-distro hit the same thing.

**Firefox is wider than a phone.** Its window is at least 500 points wide,
which at the phone's 2x scale is wider than the screen in portrait, so it
could not be maximized and hung off the side. The launcher sets Firefox's
zoom to fit the screen's width on the phone, and leaves it alone on a
monitor. Firefox also sizes its window again just after mapping it, so
`linx-windows.py` maximizes new windows a second time shortly after.

**Xvnc is not black.** The display once used Xvnc, and every window came
through black while the root window was fine, so it was replaced with Xvfb
plus x11vnc. The real cause was the client's Hextile decoder, which lost
every tile with detail in it - which is all of the drawn content. With the
decoder fixed Xvnc is back, because it is the one that can change size.

**Xvfb cannot resize, and does not pretend to.** Its RandR reports the
starting size as the maximum, `xrandr --newmode` and `--setmonitor` are
accepted and then ignored. Only an X server that implements resizing (Xvnc
here) lets the desktop follow a rotation.

**A physical keyboard brings up the soft keyboard.** Key events take
Android out of touch mode, and Compose then focuses the first focusable
element - here the hidden field that feeds the soft keyboard - which shows
the keyboard. The field can only take the focus after the keyboard button
asked for it.

## Verification performed

- Rootfs built and validated for arm64, armhf and amd64.
- Container validated in a `chroot`: `dpkg` parses, `apt update` and
  `apt install` work, TLS works (`curl` → HTTP 200), Flathub remote registers
  with the correct description and GPG key, and `flatpak install
  org.gnome.Maps` completes successfully.
- The Kotlin tar extractor was run against the real 316 MB archive and
  matches GNU tar exactly: **18 644 files, 1 622 directories, 1 032 symlinks**.
- Release APKs are signed and verified with `apksigner`.
- The container runs end to end under proot on the build machine:
  `tools/integration_test.sh` starts a shell in a real Ubuntu 26.04 rootfs
  with the app's exact command line, and checks that the coreutils, the
  toolchain, the Flathub remote and the resolver all work.
- The X display was started inside the container and spoken to over RFB: the
  handshake completes and the server reports a 1024x600 framebuffer.
- The interactive login shell (`bash --login -i`) was started on a real pty
  and reports the right user, working directory and terminal type.
- **The APK runs on Android.** On an Android 14 x86_64 emulator (Pixel 7
  profile) a clean install unpacks Ubuntu, passes the first boot and comes up
  on the XFCE desktop - panel, dock, wallpaper and icons - over the app's own
  RFB client. Taps, drags and the soft keyboard reach X (typing a command
  into xfce4-terminal runs it), apps open from the launcher, and Ubuntu
  Software lists Flathub.
- **Rotation and docking on the emulator.** Rotating resizes the running
  desktop (720x1382 portrait, 1418x720 landscape, with the rail and the
  bars hidden). With a simulated 1920x1080 monitor
  (`adb shell settings put global overlay_display_devices 1920x1080/240`)
  the desktop moves there at scale 1, the phone shows the trackpad, the
  trackpad moves the pointer and clicks (opening a terminal from the dock),
  key events type into it with Shift applied, and a window open while
  docking and undocking stays open and on screen. Starting the app while
  docked starts the desktop at the monitor's size directly.
- Resizing has been run against real Xvnc from the build machine
  (`LINX_LIVE_RESIZE` in `LiveVncProbe`); the desktop reflows to the new
  size, and switching the scale to 1 and back works while it runs.
- The decoder has been run against real x11vnc, both from the build machine
  (`LiveVncProbe`) and by replaying a stream recorded on the phone
  (`VncReplayTest`); see the comments in those tests for how.

## Not yet done

- **Steam.** Steam ships no `arm64` Linux build, so it is not a realistic
  target on a phone regardless of the container.
- **Hardware acceleration.** The X server runs with Mesa's software
  rasteriser, so 3D games are CPU-bound. Compositing a desktop application
  is fine; a 3D title is not.
- **Microphone.** Sound goes from Linux to the phone, not back.
- **A physical phone.** Everything has run on the x86_64 emulator; the arm64
  APK has not been tried on real hardware yet.
- **Flatpak's sandbox.** Flatpak apps run (see Flatpak under proot), but
  without their sandbox, and Flatpak's portals (file choosers that reach
  outside the app, for instance) are untested. Several Flathub apps were
  not tried at all; mpv was.
- **Snaps.** Ubuntu ships some apps (Chromium, Thunderbird) only as snaps,
  which need snapd and mounts; they are not available.
- **Clipboard beyond Latin-1.** The basic RFB clipboard cannot carry
  other characters; TigerVNC's extended clipboard could.
- **Docking on real hardware.** Docking has run against the emulator's
  simulated monitor only. Whether a given phone offers its USB-C video
  output as a presentation display (rather than only mirroring) varies.
- **Capturing a real mouse.** Moving a physical mouse through pointer
  capture in dock mode has not been tried with a real mouse yet.
