# Mobuntu feature guide

Everything Mobuntu does, in more detail than the [README](../README.md).

## Ubuntu 26.04 desktop shell
Native Compose implementation of the GNOME 50 / Yaru look: top bar with
Activities, centred clock and status indicators, an app grid with search, a
dash, and a home panel showing live guest information (kernel, architecture,
Flatpak version, Flathub remotes, disk usage).

## Terminal
A real POSIX PTY (implemented in NDK/C++, because Android's bionic has no
`forkpty`), so interactive programs, colours, readline and job control all
behave normally. A small ANSI parser handles SGR colour, erase and cursor
sequences.

## Ubuntu Software
A native store with two sources, switched at the top:

- **Flathub** (first): the whole Flathub catalogue, as flathub.org shows
  it, read from Flathub's web API (`FlathubApi.kt`). The front page has
  the popular apps and the categories (Games, Office, Graphics, ...), only
  apps built for this phone's processor. Each app has a page with its
  screenshots and description. A search also finds apps built only for PC
  processors, marked **PC only**, so someone looking for one sees why it
  is not there. Without internet, the store falls back to the apps this
  version knows and Flatpak's own list. The first install turns Flatpak
  on (`linx-flatpak.sh`) and brings the app's runtime, a few hundred MB.
  Flatpak prints its progress only to a terminal, so installs run under
  `script`, and the store and setup show the real percentage.
- **Front page**: like GNOME Software, a spotlight app (a different one
  each day, from your interests), then a row for each collection (Work and
  school, Gaming, Music and video, Creating, Chat and social, Coding, Other
  browsers, Handy tools) with "See all". The gaming collection is
  emulators for every classic console, Moonlight for streaming from a PC,
  and open-source games, with a note on what runs well without the graphics
  chip.
- **Ubuntu**: a featured page (Firefox, LibreOffice, GIMP, Inkscape, VLC,
  Geany, ...) and a search of the Ubuntu archive. Backed by
  `apt-install.sh`, which fetches apt's package lists the first time (the
  image ships without them) and installs with `apt-get`.

Installed apps from either appear in the desktop's Applications menu.

## Steam goes to DroidDeck
Steam on an ARM phone needs three things Mobuntu does not have: Valve's
ARM build of Steam and Proton, FEX to run PC code, and games drawn on the
phone's GPU (Mobuntu draws its desktop without the GPU, like a remote
desktop). [DroidDeck](https://github.com/Droid-Deck/DroidDeck) is a separate
open-source app that has all three, so Mobuntu hands Steam to it
(`DroidDeck.kt`). On ARM phones, All apps has a **Steam** entry, and
Flathub's Steam and Steam Link say **Play** or **Get DroidDeck** instead of
**PC only**. Play opens DroidDeck (`com.droiddeck.launcher`); Get DroidDeck
opens its releases page, the only place it is published.

## Flatpak under proot
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

## Onboarding
The first time the app starts it asks, Ubuntu-installer style, how the
Linux should be (`OnboardingScreen.kt`), while Ubuntu unpacks behind it:

- **How much**: Light (the desktop, terminal and files), Standard (adds
  Firefox and a document viewer) or Full (adds LibreOffice, GIMP and VLC),
  with the download each means.
- **What will you use it for?** Work and school, Gaming, Music and video,
  Creating, Chat and social, Coding: pick any. Each ticks a few apps
  suggested for it (`Collections.kt`), and the store shows those
  collections first.
- **Look**: light or dark, one of Yaru's eight accent colours, a
  wallpaper, and the desktop's size on the phone (a monitor always gets a
  full-size desktop).
- **Apps**: the apps for each interest, from Ubuntu or Flathub, and
  Ubuntu's essentials, ticked by the choices above, to add to or take
  from. Only apps built for the phone's processor are offered: on ARM,
  Betterbird stands in for Thunderbird, which Flathub builds only for PCs.
- **For developers** (when Coding is picked): popular open-source tools from GitHub that Ubuntu
  packages - Git, GitHub CLI, Neovim, lazygit, btop, fastfetch, ripgrep,
  fzf, bat, zoxide, tmux - each on its own or all at once.
- **App stores**: Ubuntu's archive always; Flathub on or off. Setup skips
  a Flathub app that is not built for the phone's processor rather than
  failing, and a failed setup's banner can be dismissed.

Setup moves (`SetupEffects.kt`): a slow glow in the colour being picked
with specks of light rising through it, a logo that lands with a bounce,
pages whose parts rise in one after another, cards that give under the
finger with a light haptic tick, and confetti at the end. It is all drawn
by Compose on one canvas, and it holds still when the phone is set to
remove animations. "Starting the desktop" shares the background and shows
a tip every few seconds.

The choices go to `/etc/linx/setup.conf`, and once the desktop is up
`linx-setup.sh` installs them in the background - sound, Ubuntu's look and
the everyday apps (an image viewer, an archive manager, a calculator, file
thumbnails, clipboard history) always, then the rest - while a card at the top of the desktop shows the
step, what apt or Flatpak is doing and a progress bar (`SetupBanner.kt`,
from apt's `APT::Status-Fd` lines). If it fails, tapping the card retries. It
copies the file to `setup.done` when everything is in, so an interrupted
setup carries on next time. Settings' "Set up" goes through it again: the
look changes at once, and only what is missing is installed.

## Firefox
Firefox is the desktop's browser, installed by setup unless left out. Ubuntu's own `firefox` package only
installs the snap, which cannot run here, so it comes from Mozilla's apt
repository (`install-firefox.sh`), pinned above Ubuntu's. Mozilla builds it
for amd64 and arm64; on 32-bit ARM the app installs GNOME Web instead.

## Sound
PulseAudio in the container plays into a virtual sound card ("Phone") and
streams it as raw PCM on 127.0.0.1:4713 (`linx-audio.sh`). The app plays
that stream through Android (`AudioBridge.kt`), so Firefox, VLC and the rest
come out of the phone's speaker, headphones or Bluetooth. Silence is not
passed on, and the Android track pauses when nothing plays.

## Clipboard
Copy on the phone, paste in Linux, and the other way round, in any
language and with emoji. The app syncs with Android's clipboard when it
changes and when the app comes back to the front.

- **Linux to the phone** uses TigerVNC's extended clipboard (the
  `0xC0A1E5CE` pseudo-encoding), which carries zlib-compressed UTF-8.
  Servers without it fall back to the basic RFB clipboard, which is Latin-1.
- **The phone to Linux** goes as basic ClientCutText when the text is
  Latin-1. Other text goes to `linx-clip.py`, which owns the X CLIPBOARD
  selection itself (through ctypes, like `linx-windows.py`) and serves it as
  UTF-8 until something else is copied. Offering it through the extended
  clipboard instead gets Xvnc killed with SIGSYS by Android's system call
  filter. The same Xvnc build under strace outside Android makes only
  ordinary calls, so the cause is still unknown.

## Phone folders
With storage permission (asked the first time; Settings can ask again),
Downloads, Documents, Pictures, Music and Videos in the Linux home folder
are the phone's own (`linx-folders.sh`), so a file saved in Firefox shows
up in the phone's Downloads, and a photo is one click away in GIMP.

## Sharing between the phone and Linux
- **Open in Mobuntu.** It is in Android's share menu and Open with list
  (`SharedIn.kt`). Shared files are copied into `~/From phone`, and the app
  asks which Linux app opens them: `linx-apps.py --handlers` lists the apps
  whose menu entries take that MIME type, the default first. Shared text
  becomes a note, and a shared link opens as it is.
- **Phone links from Linux.** `tel:`, `sms:`, `geo:` and `mailto:` links open
  with the phone's own apps. `linx-apps.py --tidy` makes `linx-phone` their
  handler (mailto only while no Linux mail app is the default), and
  `linx-phone open` sends the link over the notification socket. The app
  opens only those schemes and web links.

## The phone's apps in Linux
Every launcher app on the phone is in the Linux menu under **Phone apps**
(`AndroidApps.kt`). It gets a menu entry with its icon that runs
`linx-phone app <package>`; a name that clashes with a Linux app's gets
"(phone)". The entries are rewritten only when the phone's apps change.
When docked, they open next to the desktop on the monitor.

## One search
The search in All apps also finds the phone's apps, files in the Linux
home (including the phone's folders), and Flathub apps not yet installed,
which open in Software.

## Following the phone
With **Match my phone** on (Settings → Look, on by default), Linux's light or
dark mode and accent follow Android's. The accent is the Yaru colour nearest
the wallpaper colour Android 12 and newer pick (`PhoneLook.kt`). The time
zone has always been the phone's. The language is the phone's from the next
start: `linx-locale.sh` compiles the locale and installs Ubuntu's
translations, and `LinuxRuntime` sets `LANG` from `/etc/linx/locale`.

## Installs take turns, and reach the internet
Setup, the store, updates and the desktop's background extras all install
through `apt-install.sh` (or `linx-updates.sh`), which share one lock
(`/tmp/linx-apt.lock`): two apt-gets at once fail on apt's own lock, which
showed up as "could not download the package lists" when the background
extras started during first-time setup. Installs wait for their turn; a
store search or an update check does not, and makes do with the lists
there are. The lock is closed for everything the scripts start, so a
service started by a package cannot keep it.

Linux's `/etc/resolv.conf` gets the DNS servers of the network the phone
is using (not of every network it has: mobile data and the carrier's
calling network stay up on Wi-Fi, and Linux only asks the first three),
then a public one, and follows network changes. If `apt-get update` still
fails, `apt-install.sh` tries public DNS servers, then Ubuntu's archive
over HTTPS, and reports apt's own error.

## Install lines from the web
Instructions for installing a Linux app usually start with `sudo`, and are
as often written for Arch (`pacman`, `yay`), Fedora (`dnf`) or snap as for
Ubuntu. Typed into the terminal they ended in "command not found".
`linx-pkg` answers them instead (`ContainerSetup` installs a two-line shim
per command in `/usr/local/bin`, never in front of a real one):

- `sudo CMD` runs CMD: there is only root.
- `pacman -S NAME`, `yay`, `paru`, `dnf install`, `yum`, `zypper`, `apk add`
  and `snap install` do the same with apt, with the name put Ubuntu's way
  (`python-pip` is `python3-pip`, `base-devel` is `build-essential`), and
  from Flathub when Ubuntu does not have it. `-Ss`/`search`, `-R`/`remove`
  and `-Syu`/`upgrade` are translated too.
- `flatpak install NAME` takes a plain name (`mpv` finds `io.mpv.Mpv`) and
  an id in the wrong case, sets Flathub up first if it is not, and says so
  when an app is built only for other processors (Flathub's API lists them)
  or does not exist, with what Ubuntu or Flathub has instead.
- In a terminal (`/etc/profile.d/linx-shell.sh`, also sourced from
  `/etc/bash.bashrc`), a failed `apt install` says what the name is in
  Ubuntu, and an unknown command says whether Ubuntu has it.
- `linx-pkg install NAME` and `linx-pkg search WORD` look in both.
- Some apps need more than a name, however they are asked for (`apt`,
  `pacman`, `flatpak`, a failed install's hint):
  - **Steam** on an ARM phone: said to run in DroidDeck, and `steam` opens
    it, or the page to get it from (`linx-phone steam`).
  - **Thunderbird** and **Chromium**: Ubuntu's packages are stubs that
    install a snap, which cannot run here (any candidate version with
    "snap" in it counts as not in Ubuntu), so they come from Flathub;
    on ARM, Betterbird stands in for Thunderbird.
  - **Discord**, **Chrome** and **Spotify** on ARM: built only for PCs;
    Vesktop and Chromium are offered instead, and Spotify's web player.
  - Docker, Wine and virtual machines install with a note on what cannot
    work under Android.
- A name nothing matches gets Ubuntu's and Flathub's nearest (programs,
  not libraries), trying one letter less for a typo.
- Apps installed with plain `apt` get the menu clean-up too
  (`/etc/apt/apt.conf.d/99linx-tidy` runs `linx-apps.py --tidy` after dpkg).

Scripts are not affected: the shell functions are for interactive shells
only.

## Battery, network and volume in the panel
Next to the clock, as on a laptop:
- **Network:** Wi-Fi or mobile data with signal bars, Ethernet, VPN,
  airplane mode or offline. The tooltip also says when the network is
  metered or has no internet yet.
- **Volume:** XFCE's PulseAudio plugin.
- **Battery:** the level and charging state. The tooltip adds the
  percentage, charger type, time until full, temperature and battery
  condition.
- **Notifications:** xfce4-notifyd's bell keeps what came in, with Do not
  disturb.

Linux under proot cannot see the phone's battery or network, so while Linux
runs, `PhoneStatus.kt` writes them to `/tmp/linx-phone-status`. Two generic
monitor panel items run `linx-status battery|network` on that file every
10 seconds. Starting a program is slow under proot, so the script uses
bash's builtins only and starts none. The status icons are styled smaller
than the dock's and white (`~/.config/gtk-3.0/gtk.css`, from
`linx-panel.sh`). Setup installs `xfce4-genmon-plugin`; a Linux set up earlier gets it
in the background the next time the desktop starts.

## Quick settings, lock, screenshots
Clicking the network or battery item (or Quick settings in the app's menu)
opens a panel drawn by the app (`PhoneExtras.kt`):
- **Wi-Fi, Bluetooth and Torch** switch the phone's own. The app targets
  Android 9, which still lets it switch them. Long-press a tile for its
  settings page.
- **Night light:** a warm tint over the desktop, with a warmth slider.
- **Keep awake.**
- **Screenshot.**
- **Brightness** for the desktop alone. "Phone's brightness" goes back to
  the phone's own setting.

The panel also has links to the phone's internet and battery settings.

Other controls:
- **Lock** (Super+L, Ctrl+Alt+L, the menu's lock button, or `linx-phone
  lock`) covers the phone and a connected monitor with a clock. Unlocking
  takes the phone's PIN, pattern or fingerprint.
- **Print Screen** (or the Screenshot menu entry) saves the desktop to the
  phone's Pictures/Screenshots.
- **Battery warnings:** at 15% and 5% on battery, a "Battery low"
  notification appears on the desktop.

## Phone notifications on the desktop
With access allowed (Settings → Phone notifications), new phone
notifications (not ongoing ones such as music or downloads) also pop up on
the Linux desktop and wait in the bell. This only happens while the desktop
is on screen, on the phone or a monitor. `PhoneNotifications.kt` leaves them
as JSON in `/tmp/linx-phone-notes`, and `linx-notify.py` shows them with
`notify-send`. Their app name ends in "(phone)", so they are not sent back
to the phone.

## Printing
Linux apps print to the "Phone" printer. CUPS-PDF writes the job as a PDF
in `/tmp/linx-print`, and Android's print dialog opens with it, offering the
phone's printers (Wi-Fi printers, Mopria and others) or Save as PDF.
`linx-print.sh` sets CUPS up so it listens only on its local socket, because
Android keeps apps off port 631. CUPS starts with the desktop.

## LibreOffice and /proc
Android 8 and newer deny apps `/proc/version`, `/proc/stat`, `/proc/uptime`,
`/proc/loadavg` and `/proc/vmstat`. Without them, LibreOffice's launcher
stops with "/proc not mounted", and `ps` cannot find the boot time.
`LinuxRuntime` binds plausible stand-ins over each one the app cannot read,
as proot-distro does.

## Not possible yet: the camera
Linux apps see a camera through a kernel video device (`/dev/video*`, or
v4l2loopback to make one). Android gives apps neither, and proot cannot add
a kernel module, so video calls in Linux apps have no camera. Calls work in
the phone's own apps.

## Microphone
Linux apps can record from the phone's microphone ("Phone microphone", the
default input). PulseAudio takes 48 kHz mono PCM on 127.0.0.1:4714 into a
null sink, and its monitor is remapped as a source (`linx-audio.sh`).
`linx-mic.sh` watches for recordings with `pactl subscribe` and writes
`/tmp/linx-mic-wanted`. `MicBridge.kt` only records, and Android only shows
its microphone indicator, while that says 1. Android asks for the
permission the first time a Linux app records. The foreground service (see
Keeping Linux running) keeps it working while other apps are in front.

## Look
Ubuntu's Yaru theme and icons, light or dark in the chosen accent colour,
and the Ubuntu font (`linx-look.sh`), a dark bottom panel, and window
borders drawn for the app by `make-xfwm4-theme.py` to match - a flat title
bar with round minimise and maximise buttons and a close button in the
accent colour - at 1x for a monitor and 2x for the phone.

## Notifications on the phone
Linux apps notify through `org.freedesktop.Notifications`, and xfce4-notifyd
shows those notifications on the desktop. `linx-notify.py` watches the same
calls with `dbus-monitor` and sends each one to the app over an abstract
unix socket (`<package>.notify`). While the app is in the background, it
posts each as an Android notification with the app's own icon
(`NotificationBridge.kt`). The notification channel is created the first time
the desktop is on screen, because Android only asks an app built for older
versions whether it may notify while that app is in the foreground.

## Linux apps on the home screen
Long-press an app in "All apps" and choose **Add to home screen**. The
shortcut carries the app's id and opens it on the desktop. If the app is
not running, the shortcut starts it first and waits until the desktop has
finished starting, so the app does not race the window manager.

## Snapshots
Settings → Snapshots saves the whole system to a `.tar.zst` file wherever you
choose, through Android's file picker, and restores one the same way
(`linx-snapshot.sh`). tar runs in the container and is streamed straight to
the file, so there is no copy in between. On the emulator it wrote about
12 MB/s. A save that fails deletes its partial file. Restoring replaces the
files the snapshot has and keeps files you added since.

**Every week** saves one by itself into a folder chosen once, kept with a
persistable permission. It runs a minute after the desktop is up, at the
lowest priority (`nice -n 19`), and keeps the newest two.

## Keeping Linux running
Android freezes or kills an app soon after it leaves the screen, and Linux
with it. While the desktop is up, the app runs a foreground service
(`LinuxService.kt`) with a quiet "Linux is running" notification, so Linux
keeps going while other apps are in front, and Linux apps keep the
microphone. The notification's **Keep awake** holds a partial wake lock, so
long jobs go on with the screen off. **Stop Linux** ends the app, and every
Linux process with it.

Two Android settings still apply (`BackgroundLimits.kt`); Settings → **Keep
Linux running** shows both and links to them, and the app points there once,
the first time the desktop is up:
- **Child process restrictions.** Since Android 12, Android kills an app's
  extra processes when there are more than 32 or when they use the processor
  in the background. Every Linux process is one of those. Android 14 and newer
  have **Disable child process restrictions** in Developer options. It is a
  feature flag, kept as the system property
  `persist.sys.fflag.override.settings_enable_monitor_phantom_procs`, not as
  a global setting, so the app reads it with `getprop`.
- **Battery optimisation**, which the app asks to be left out of.

## Home screen
For people whose phone is their only computer, **Settings → Home screen** (and
the last step of setup) makes Mobuntu the phone's home app. Home then opens
the app grid: every phone app first (long-press for the app's Android page,
to uninstall it), then the Linux apps, with the Linux desktop behind the
button in the corner. Back stays on the grid.

Android keeps the home app in a task of its own, so the home activity is not
MainActivity: Home there would start a second copy, with a second Linux
display. `HomeActivity` is a see-through stand-in, disabled until the option
is turned on, with its own task affinity. Sharing MainActivity's task, Home
would close MainActivity. Each time Home brings the stand-in forward, it
brings MainActivity forward on its grid (`HomeScreen.kt`). Android 10 and newer
ask through the home role; turning it off disables the stand-in, so Android
goes back to the phone's own launcher. The screen stays on only while the
Linux desktop is in front, not on the grid, so the phone still sleeps.

## Updates
Software's **Updates** tab lists Ubuntu package and Flathub app updates
(`linx-updates.sh --check`), and **Update all** installs them, with apt's and
Flatpak's progress on a bar. The app checks once a day when the desktop
comes up and posts a notification when updates are waiting; tapping it opens
the tab. Flatpak prints its progress only to a terminal, so installs and
updates run under `script`.

## An app that does not open says why
An app started from the Apps screen (or a home-screen shortcut) runs under
`linx-apps.py --run`, which keeps its output in `/tmp/linx-run/ID.log` and
waits for it. If it stops with an error before any window has appeared, the
log's last telling line goes to the desktop as a notification and to the
app (`/tmp/linx-run-failed`), which shows "X did not start", the reason,
and **Share log**. Before, such a tap looked like it did nothing. The
"Opening..." pill adds, after 12 seconds, that big apps can take a minute
or two: Flathub apps start through two proots (`linx-bwrap`), and Krita
takes about a minute on a fast machine.

## Flathub apps and Android's 32-process limit
Since Android 12 the "phantom process" monitor kills an app's child
processes when there are more than 32, and every Linux program is one. The
desktop alone is about 35; a Flathub app adds ten or so (two proots, the
stand-in bwrap, the D-Bus proxy, Flatpak's portal and session helper).
With the limit on, opening Telegram from Flathub ended with Android
killing Telegram, the panel, D-Bus and most of the desktop
(`PhantomProcessRecord ... died` in logcat): on the phone, an app that
"acts as if it is opening and never opens". It passed every emulator test
because the emulator had the limit off.

The cure is Android's own switch, Developer options → **Disable child
process restrictions** (Android 14 and newer), which Settings' "Keep Linux
running" has always pointed to. Now tapping a Flathub app while the limit
is on says so first, with a button to Developer options and "Open anyway".

## One look for the whole app
Apps, Software and Settings share setup's look (`Design.kt`): the dark sky
with the accent glowing through it, edge to edge under transparent system
bars, and on it translucent cards, rows that lead with an icon on a tinted
tile, segmented choices, chips, one search field and one kind of button.
`MobuntuScreen` is the frame every screen uses: the backdrop, a round back
button, a large title, and the page fading in.

- The backdrop is `AuroraBackground(animated = false)`: one still frame,
  drawn once, so scrolling over it costs nothing. Only setup and "Starting
  the desktop" animate it.
- **Apps**: an even grid (every tile fills its cell, one icon size, names
  on up to two lines). The phone's apps are read off the main thread and
  kept for the next time the screen opens.
- **Settings**: what stops Linux from running well comes first, and only
  while there is something to do; then Appearance, With your phone, Linux
  and About, each a card of rows.
- **Software**: the same frame; an app's page drops the search field and
  tabs to give the app the room.
- The desktop's side menu is glass with the accent on its icons, and
  "Starting the desktop" stays up until the first picture has arrived
  (`DisplaySession.frames`), instead of a black screen.

## The app in your colours
The accent colour chosen at setup colours the app as well as Linux: the
floating button, menus, Settings, the store and the system bars
(`UbuntuPalette.accent`, with dark backgrounds derived from it). Settings
has a **Look** card for light or dark, the accent and the wallpaper. A
change there applies to the app and the desktop at once, with nothing
reinstalled.

## Typing
The phone's keyboard comes up by itself after a tap on a text field. X has no
"a field has focus" signal, but over a text field the pointer becomes a text
cursor. `linx-windows.py` follows cursor changes through XFixes and writes the
cursor's name to `/tmp/linx-cursor`, and after a tap the app checks for
"text". This needs a cursor theme, because the core X cursors have no names;
`linx-look.sh` sets Yaru's.

## Wallpapers
Six wallpapers (Aubergine, Aurora, Dunes, Ridges, Graphite, Dawn), drawn by
the app with Android's canvas (`Wallpapers.kt`) rather than shipped as
pictures: the same code draws onboarding's previews and the 2048-pixel
files it writes to `/usr/local/share/linx/wallpapers`, where `linx-look.sh`
sets the chosen one for xfdesktop (and feh under Openbox).

## Finding apps
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

## The app around the desktop
The desktop fills the screen; the status and navigation bars hide until
swiped in. A small floating button at the right edge (drag it up or down)
opens a side menu with everything that is the app's rather than Linux's: the
app grid, Firefox, Terminal, Files, Software, the keyboard, the
gamepad, docking, reconnecting and **Settings**. Settings has the desktop
size (Compact, Standard - the default, sized for a 6-inch phone - and
Large; the desktop resizes as soon as it is changed), full screen on or off,
the browser, and version details.

## Linux Display — running real X11 apps
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

## Virtual gamepad
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

## Rotation
The desktop follows the phone. Rotating it resizes the X screen to the new
shape (it is never restarted, and open windows stay open): in portrait the
app's actions sit in a bar above the desktop, in landscape they move to a
rail at the side and the status and navigation bars hide until swiped in.
The shell, gamepad and terminal re-layout too.

## Docking: a desktop on a monitor
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

## Desktop layout

Settings → Appearance → **Layout** picks how the desktop's bars look:
**Mobuntu** (one bar at the bottom with the app grid, your apps and the
clock), **Windows** (a taskbar with a Start button, open windows with their
names and the clock on the right) or **Ubuntu** (a top bar with the clock in
the middle and a dock at the bottom). The change applies at once.

## Touch modes

The menu's **Touch mode** (under Controls) chooses how a finger works the
mouse. **Touch**: tap where you want to click, hold to right-click.
**Trackpad**: slide to move the pointer, tap to click where it points,
two-finger tap to right-click, double-tap and slide to drag. **Stylus**: a
pen hovers to point, touches to click or draw, and its button right-clicks;
fingers only scroll, so a resting hand does nothing.

## Performance presets

Settings → **Performance** offers Basic, Balanced and Fast, and recommends
one from the phone's memory. Basic leaves out the desktop icons, the file
manager's background service and printing to save memory; Fast turns
animations on.

## Updating Mobuntu

The app checks GitHub for a new release and shows a notification when one is
out. Settings → About → **Check** looks straight away, and **Update**
downloads the APK and installs it over the current one, keeping Linux and
everything in it.

## Uninstalling apps and turning Linux off

Software's **Installed** tab lists the apps you added, Ubuntu packages and
Flathub apps alike, each with an **Uninstall** button. The menu's **Power**
turns Linux off, or restarts it, so nothing keeps running in the background.
