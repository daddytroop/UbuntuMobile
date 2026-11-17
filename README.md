# Mobuntu

[![Build APK](https://github.com/daddytroop/UbuntuMobile/actions/workflows/build-apk.yml/badge.svg)](https://github.com/daddytroop/UbuntuMobile/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/daddytroop/UbuntuMobile)](https://github.com/daddytroop/UbuntuMobile/releases/latest)
[![License: GPL v2+](https://img.shields.io/badge/license-GPL%20v2%2B-blue)](LICENSE)

**A full Ubuntu 26.04 desktop in an Android app. No root needed.**

Mobuntu runs a real Ubuntu system on your phone or tablet. You get an XFCE
desktop with Firefox, a terminal with `apt`, and an app store for Ubuntu and
Flathub apps. Plug the phone into a monitor and it becomes a desktop computer.

## Download

Get the latest APK from the
[**Releases page**](https://github.com/daddytroop/UbuntuMobile/releases/latest):

| File | For |
|---|---|
| `app-arm64-release.apk` | Most phones and tablets |
| `app-armhf-release.apk` | Older 32-bit phones |
| `app-amd64-release.apk` | x86 tablets, Chromebooks and emulators |

Open the APK on the phone and allow installs from unknown sources when
Android asks. The first start unpacks Ubuntu (about a minute), then a short
setup asks what you will use it for and installs matching apps.

**Updating:** Mobuntu tells you when a new version is out and installs it
from inside the app (Settings → About → Check). Your Linux files and apps are
kept.

**Requirements:** Android 8.0 or newer, a few GB of free space (more for the
apps you add), and 4 GB of RAM or more (8 GB is comfortable).

## Features

**The desktop**
- XFCE desktop with Firefox, Files, Terminal and Ubuntu's Yaru look.
- Three layouts: Mobuntu (one bar at the bottom), Windows (taskbar and Start
  button) or Ubuntu (top bar and dock).
- Three touch modes: **Touch** (tap where you click), **Trackpad** (slide to
  move the pointer, like a laptop trackpad) and **Stylus** (for S Pen and
  other pens).
- Performance presets (Basic, Balanced, Fast), recommended from your
  phone's memory.
- An on-screen gamepad with an Xbox layout for Linux games.

**Apps**
- **Ubuntu Software:** browse, install, update and uninstall Ubuntu packages
  and Flathub apps, with categories and search.
- A real terminal with bash, `apt`, GCC, Python and Git.
- Steam is handed over to DroidDeck.

**Works with your phone**
- Sound, a microphone and a shared clipboard.
- Your phone's Downloads, Pictures and Documents appear in Linux.
- Linux notifications show on the phone, and phone notifications on the
  desktop.
- Battery, network and volume in the panel; dark mode and accent colour
  follow Android.
- Links, phone numbers and emails clicked in Linux open in your phone's
  apps.
- Optional: use Mobuntu as your home screen, with phone and Linux apps in
  one grid.

**Docking**
- Connect a monitor over USB-C and the desktop moves to it at full size,
  with the phone as a trackpad and keyboard. Works with Samsung DeX too.
- Bluetooth or USB mice and keyboards work.

**Keeping it running**
- Linux keeps running in the background, with a notification to stop it,
  and Power in the menu to turn it off or restart it.
- Snapshots back up your Linux system and restore it.

See the [feature guide](docs/FEATURES.md) for the details.

## What is preinstalled

| Area | Contents |
|---|---|
| Shell & core | bash, coreutils, findutils, grep, sed, tar, gzip/zstd/xz, jq, ripgrep |
| Build | `build-essential` (gcc/g++/make), cmake, ninja, pkg-config, python3-dev |
| Languages | Python 3.14 + pip + venv, git, gnupg |
| Network | curl, wget, openssh-client, iproute2, dnsutils, socat |
| Flatpak | flatpak, bubblewrap, appstream, Flathub remote |
| Media / GL | mesa, libGL/libEGL/libGLES, pulseaudio-utils, alsa-utils |
| Fonts | DejaVu, Noto core, Noto colour emoji |

## How it works

Mobuntu unpacks a genuine Ubuntu 26.04 system, built from Canonical's
`ubuntu-base`, into the app's storage and runs it with
[proot](https://proot-me.github.io/), so it needs no root and leaves Android
untouched. The desktop runs on an X server inside Ubuntu and is drawn by the
app's own display client. The home screen, store, settings and terminal are
native Android (Kotlin and Jetpack Compose).

```
┌─────────────────────────────────────────────┐
│  Android app (Kotlin + Jetpack Compose)      │
│  home · store · settings · terminal · display│
└──────────────────────┬──────────────────────┘
                       │  proot
┌──────────────────────▼──────────────────────┐
│  Ubuntu 26.04 rootfs                         │
│  XFCE · Firefox · apt · Flatpak · Xvnc       │
└─────────────────────────────────────────────┘
```

## Known limits

- **No GPU acceleration.** Graphics are drawn by the CPU, so desktop apps are
  fine but 3D games are slow.
- **Snaps don't work.** Apps Ubuntu ships only as snaps aren't available; many
  of them are on Flathub instead.
- **No camera** in Linux apps yet.
- **Android may limit background processes.** Some phones stop apps that
  start many processes; setup shows how to turn that off.
- Not on the Play Store: Mobuntu has to target an older Android API to run
  Linux programs from its own storage, as Termux does.

## Building from source

You need JDK 21 and the Android SDK (platform 36, NDK 28, CMake 3.22).

```bash
./gradlew :app:assembleArm64Debug
```

Rebuilding the Ubuntu image, running the tests and publishing a release are
covered in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Contributing

Bug reports and ideas are welcome in
[Issues](https://github.com/daddytroop/UbuntuMobile/issues). If an app
won't start, tap **Share log** on the message saying it did not start, and
attach the log to the issue.

## License

Mobuntu is released under the [GNU General Public License v2.0 or later](LICENSE).
Ubuntu, Firefox and the other software it installs keep their own licenses.
Mobuntu is an independent project and is not affiliated with Canonical.
