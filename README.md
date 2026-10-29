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

Inside the container, **Xvnc** (TigerVNC) runs an X server *and* exports it
over RFB in a single process. The app talks to it with a hand-written RFB
client (`VncClient.kt`), decoding Raw, CopyRect and Hextile encodings.

Two properties make this work without any extra APK:

- **proot shares the host network namespace**, so the container's
  `127.0.0.1` *is* the phone's loopback. The app connects straight to
  `127.0.0.1:5901` — no port forwarding, no WebSocket proxy.
- The RFB server binds to the loopback interface only, and the app speaks
  the protocol directly rather than going through a browser, so there is no
  JavaScript or noVNC payload in the APK.

If the X server does not push updates on its own, the container falls back
to **Xvfb + x11vnc**, which polls the framebuffer instead of relying on
damage notifications. display-start.sh picks whichever works.

Pixels are decoded according to the pixel format the server announces in
ServerInit rather than a format the client imposes, so the colours are
right whatever the server prefers (32bpp BGRX, 16bpp RGB565, big-endian
or an unusual channel order). The decoder is covered by a hermetic test
that runs a fake RFB server and asserts every pixel matches.

Touch is translated into pointer events and the on-screen keyboard supplies
key events, so ordinary X applications (xterm, xeyes, GTK apps) can be
driven from the phone.

### Virtual gamepad
An Xbox-layout on-screen controller (dual sticks, D-pad, ABXY, triggers,
shoulders) that adapts between portrait and landscape. State is streamed to
the container, where `gamepad-helper.py` turns it into a virtual uinput
joystick.

> **Note:** `/dev/uinput` is not exposed to unprivileged apps on most Android
> devices, so the Linux-side virtual pad only works on rooted devices or
> ROMs that permit it. The app detects this and says so instead of pretending
> it works. The on-screen controls themselves are always usable.

### Orientation
The shell, gamepad and terminal all re-layout on rotation. The activity
handles `orientation|screenSize` itself so the container is never restarted.

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
shifts are 1 byte. In `Hextile`, the sub-rectangle count lives in bits 3-7
of the subencoding byte (0 means sixteen) and a sub-rect byte can
legitimately be `0x00`, so the loop must use the count rather than looking
for a terminator.

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

## Not yet done

- **Steam.** Steam ships no `arm64` Linux build, so it is not a realistic
  target on a phone regardless of the container.
- **Hardware acceleration.** The X server runs with Mesa's software
  rasteriser, so 3D games are CPU-bound. Compositing a desktop application
  is fine; a 3D title is not.
- **Audio.** The container has PulseAudio utilities, but there is no audio
  bridge from the container to the phone's audio stack yet.
- **On-device verification.** The build host has no KVM, so no emulator was
  available. Everything above was verified at the container, protocol and
  unit-test level; the APK itself has not been run on hardware.
