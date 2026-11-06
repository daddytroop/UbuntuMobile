# UbuntuMobile

[![Build APK](https://github.com/daddytroop/UbuntuMobile/actions/workflows/build-apk.yml/badge.svg)](https://github.com/daddytroop/UbuntuMobile/actions/workflows/build-apk.yml)
**A complete Ubuntu 26.04 Linux system that runs on an Android phone.**

One APK gives you a real Linux userland — bash, apt, GCC, Python, Git, Flatpak
and the whole Flathub catalogue — driven from a native desktop shell that looks
and behaves like Ubuntu 26.04.

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
│  • Ubuntu Software — browse/install Flathub   │
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
Browse and install from Flathub with a native UI. Backed by
`flatpak remote-ls` and `flatpak install` inside the container.

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
shoulders) that adapts between portrait and landscape. State is streamed to
the container, where `gamepad-helper.py` turns it into a virtual uinput
joystick.

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
│   │   │   │   ├── TarExtractor.kt       streaming tar (unit tested)
│   │   │   │   └── Pty.kt                PTY session
│   │   │   ├── ui/                       desktop, terminal, store
│   │   │   └── gamepad/                  virtual controller
│   │   ├── cpp/linx_pty.cpp              PTY implementation (NDK)
│   │   ├── assets/proot/<abi>/           proot, loader, libtalloc, libandroid-shmem
│   │   └── res/                          theme + launcher icon
│   ├── src/arm64/assets/rootfs/          Ubuntu 26.04 arm64 image
│   ├── src/armhf/assets/rootfs/          Ubuntu 26.04 armhf image
│   └── src/amd64/assets/rootfs/          Ubuntu 26.04 amd64 image
├── tools/                                rootfs build pipeline (WSL)
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
- **Audio.** The container has PulseAudio utilities, but there is no audio
  bridge from the container to the phone's audio stack yet.
- **A physical phone.** Everything has run on the x86_64 emulator; the arm64
  APK has not been tried on real hardware yet.
- **Running Flatpak apps.** Installing from Flathub is wired up, but Flatpak
  starts every app inside bwrap, which needs user namespaces that proot
  cannot provide, so an installed Flatpak is not expected to launch yet.
- **Docking on real hardware.** Docking has run against the emulator's
  simulated monitor only. Whether a given phone offers its USB-C video
  output as a presentation display (rather than only mirroring) varies.
- **Capturing a real mouse.** Moving a physical mouse through pointer
  capture in dock mode has not been tried with a real mouse yet.
