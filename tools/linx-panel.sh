#!/bin/bash
# The panel's app menu and dock. Runs inside the container, in the desktop
# session.
#
#   linx-panel.sh             set them up in xfconf (before the panel starts)
#   linx-panel.sh --restart   and restart a running panel to pick them up
#
# Once linx-setup.sh has installed them:
#   - Whisker Menu for the app button: a search field, the favourites and
#     every app in one list, with nothing hidden two levels down in
#     categories (XFCE's categories menu until then);
#   - a dock (xfce4-docklike-plugin) in place of the stock launchers and
#     the window list: pinned apps and open windows as one row of icons,
#     with a dot under the ones that are running, as in Ubuntu's dock.
set -u
export DISPLAY="${DISPLAY:-:${LINX_DISPLAY:-99}}"
# ("disabled:" is what /etc/profile.d/linx-session.sh sets while there is no desktop.)
if [ "${DBUS_SESSION_BUS_ADDRESS:-disabled:}" = disabled: ] && [ -s /tmp/linx-session-bus ]; then
  DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/linx-session-bus)"
  export DBUS_SESSION_BUS_ADDRESS
fi
[ "${DBUS_SESSION_BUS_ADDRESS:-disabled:}" != disabled: ] || exit 0
command -v xfconf-query >/dev/null 2>&1 || exit 0

# Restart the panel. desktop-start.sh watches it and starts it again when
# it ends, so it is asked to quit; "-r" would start a new one that is
# nobody's child, and so not watched.
restart_panel() {
  local w
  w="$(cat /tmp/linx-panel-watchdog 2>/dev/null)"
  if [ -n "$w" ] && kill -0 "$w" 2>/dev/null; then
    xfce4-panel -q
  else
    xfce4-panel -r
  fi
}

plugin_installed() { ls /usr/lib/*/xfce4/panel/plugins/"lib$1.so" >/dev/null 2>&1; }
get() { xfconf-query -c xfce4-panel -p "$1" 2>/dev/null; }
CHANGED=0

# Every panel item in the panel's own process. XFCE runs most plugins in a
# process each ("wrapper-2.0": seven of them here), and Android counts every
# Linux process against the 32 it lets an app have before it starts killing
# them (see README, "Android's 32-process limit"). A plugin's .desktop file
# says which way it runs; a package update puts the file back, so this is
# done at every start.
for f in /usr/share/xfce4/panel/plugins/*.desktop; do
  if grep -qiE '^X-XFCE-Internal=false' "$f" 2>/dev/null && grep -q '^X-XFCE-Module=' "$f"; then
    sed -i -E 's/^X-XFCE-Internal=.*/X-XFCE-Internal=true/I' "$f" && CHANGED=1
  fi
done

# ---------------------------------------------------------------- the menu
if plugin_installed whiskermenu && [ "$(get /plugins/plugin-1)" != whiskermenu ]; then
  P=/plugins/plugin-1
  set_prop() { xfconf-query -c xfce4-panel -p "$P/$1" -n -t "$2" -s "$3"; }
  xfconf-query -c xfce4-panel -p "$P" -n -t string -s whiskermenu
  set_prop button-icon string view-app-grid-symbolic
  set_prop show-button-title bool false
  set_prop launcher-show-description bool false
  set_prop launcher-show-tooltip bool true
  set_prop category-show-name bool true
  set_prop position-categories-alternate bool true
  set_prop recent-items-max int 8
  # Open on every app, not on the favourites.
  set_prop default-category int 2
  set_prop menu-width int 420
  set_prop menu-height int 520
  set_prop menu-opacity int 100
  # The app has its own buttons for these; and there is no session to end.
  for c in lockscreen switchuser logoutuser restart shutdown suspend hibernate logout menueditor profile; do
    set_prop "show-command-$c" bool false
  done
  set_prop show-command-settings bool true
  xfconf-query -c xfce4-panel -p "$P/favorites" -n --force-array \
    -t string -s firefox.desktop \
    -t string -s xfce4-terminal.desktop \
    -t string -s thunar.desktop \
    -t string -s org.xfce.mousepad.desktop
  CHANGED=1
fi

# No "root" and user picture at the top of the menu (Whisker follows the
# change at once, so this is set every time rather than once).
if [ "$(get /plugins/plugin-1)" = whiskermenu ]; then
  xfconf-query -c xfce4-panel -p /plugins/plugin-1/profile-shape -n -t int -s 2
fi

# ---------------------------------------------------------------- the dock
# Plugin 11 takes the place of the launchers (10, 2, 3), their separator (4)
# and the window list (5) of the stock layout (xfce4-panel.xml).
DOCK=11
if plugin_installed docklike && [ "$(get /plugins/plugin-$DOCK)" != docklike ]; then
  xfconf-query -c xfce4-panel -p "/plugins/plugin-$DOCK" -n -t string -s docklike
  # The pinned apps, the first time: those that are installed. The dock
  # keeps the list itself afterwards, as the user pins and unpins.
  RC="$HOME/.config/xfce4/panel/docklike-$DOCK.rc"
  if [ ! -s "$RC" ]; then
    PINNED=""
    for app in firefox xfce4-terminal thunar org.xfce.mousepad; do
      [ -f "/usr/share/applications/$app.desktop" ] && PINNED="$PINNED$app;"
    done
    mkdir -p "$(dirname "$RC")"
    printf '[user]\npinned=%s\n' "$PINNED" >"$RC"
  fi
  xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array \
    -t int -s 1 -t int -s "$DOCK" -t int -s 6 -t int -s 7 -t int -s 8 -t int -s 9
  CHANGED=1
fi

# ------------------------------------------------- network, volume, battery
# Next to the clock, as on any laptop: the phone's network (12) and battery
# (14), which linx-status reads from what the app writes, and the volume
# (13). Each is added once, when its plugin is installed.
add_before_clock() {
  local ids new="" id
  # Already in a bar (the Ubuntu layout has the status items in the top one).
  get /panels/panel-2/plugin-ids | grep -qx "$1" && return
  ids="$(get /panels/panel-1/plugin-ids | grep -E '^[0-9]+$')"
  echo "$ids" | grep -qx "$1" && return
  for id in $ids; do
    [ "$id" = 8 ] && new="$new -t int -s $1"
    new="$new -t int -s $id"
  done
  echo "$ids" | grep -qx 8 || new="$new -t int -s $1"
  # shellcheck disable=SC2086
  xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array $new
  CHANGED=1
}
status_item() {
  local id=$1 what=$2
  if [ "$(get /plugins/plugin-$id)" != genmon ]; then
    P=/plugins/plugin-$id
    xfconf-query -c xfce4-panel -p "$P" -n -t string -s genmon
    xfconf-query -c xfce4-panel -p "$P/command" -n -t string -s "/usr/local/bin/linx-status $what"
    xfconf-query -c xfce4-panel -p "$P/use-label" -n -t bool -s false
    xfconf-query -c xfce4-panel -p "$P/text" -n -t string -s ""
    xfconf-query -c xfce4-panel -p "$P/update-period" -n -t int -s 10000
    xfconf-query -c xfce4-panel -p "$P/enable-single-row" -n -t bool -s true
    CHANGED=1
  fi
  # Every 10 seconds is plenty (the app rewrites the status every 15, and
  # at once on a change); panels set up with 3 seconds are slowed down.
  if [ "$(get /plugins/plugin-$id/update-period)" != 10000 ]; then
    xfconf-query -c xfce4-panel -p "/plugins/plugin-$id/update-period" -n -t int -s 10000
    CHANGED=1
  fi
  add_before_clock "$id"
}
if plugin_installed genmon; then
  status_item 12 network
fi
if plugin_installed pulseaudio-plugin; then
  if [ "$(get /plugins/plugin-13)" != pulseaudio ]; then
    xfconf-query -c xfce4-panel -p /plugins/plugin-13 -n -t string -s pulseaudio
    xfconf-query -c xfce4-panel -p /plugins/plugin-13/enable-keyboard-shortcuts -n -t bool -s true
    CHANGED=1
  fi
  add_before_clock 13
fi
if plugin_installed genmon; then
  status_item 14 battery
fi
# The notification bell: what came in while you were away, phone apps'
# included, with Do not disturb (xfce4-notifyd's plugin).
if plugin_installed notification-plugin; then
  if [ "$(get /plugins/plugin-15)" != notification-plugin ]; then
    xfconf-query -c xfce4-panel -p /plugins/plugin-15 -n -t string -s notification-plugin
    CHANGED=1
  fi
  add_before_clock 15
fi
# Notifications are kept, so the bell has something to show.
xfconf-query -c xfce4-notifyd -p /notification-log -n -t bool -s true 2>/dev/null
xfconf-query -c xfce4-notifyd -p /log-level -n -t uint -s 1 2>/dev/null

# The status icons as on Ubuntu's top bar: smaller than the dock's app
# icons (the panel gives every plugin's icon its full height) and white
# like the clock, with room between them. Only these plugins are matched;
# the panel names each plugin's widget "<name>-<id>".
CSS="$HOME/.config/gtk-3.0/gtk.css"
BEGIN="/* linx-panel: status icons */"
END="/* linx-panel: end */"
STYLE="$BEGIN
#genmon-12 image, #pulseaudio-13 image, #genmon-14 image, #notification-plugin-15 image {
  -gtk-icon-transform: scale(0.62);
  color: #ffffff;
}
#genmon-12, #pulseaudio-13, #genmon-14, #notification-plugin-15,
#genmon-12 button, #pulseaudio-13 button, #genmon-14 button, #notification-plugin-15 button {
  padding-left: 1px;
  padding-right: 1px;
  margin: 0;
}
$END"
mkdir -p "$(dirname "$CSS")"
OLD="$(cat "$CSS" 2>/dev/null)"
NEW="$(printf '%s\n' "$OLD" | sed "\\|^/\\* linx-panel: status icons \\*/\$|,\\|^/\\* linx-panel: end \\*/\$|d")"
NEW="$(printf '%s\n%s\n' "$NEW" "$STYLE" | sed '/./,$!d')"
if [ "$NEW" != "$OLD" ]; then
  printf '%s\n' "$NEW" >"$CSS"
  CHANGED=1
fi

# ------------------------------------------------- keys, lock, screenshots
# Print Screen saves a screenshot to the phone's Pictures/Screenshots, and
# Super+L (or Lock in the menu) locks the desktop until the phone's own
# PIN or fingerprint: the app does both (linx-phone).
KEYS=xfce4-keyboard-shortcuts
xfconf-query -c $KEYS -p "/commands/custom/Print" -n -t string -s "linx-phone screenshot" 2>/dev/null
xfconf-query -c $KEYS -p "/commands/custom/<Super>l" -n -t string -s "linx-phone lock" 2>/dev/null
xfconf-query -c $KEYS -p "/commands/custom/<Primary><Alt>l" -n -t string -s "linx-phone lock" 2>/dev/null
xfconf-query -c xfce4-session -p /general/LockCommand -n -t string -s "linx-phone lock" 2>/dev/null
if [ "$(get /plugins/plugin-1)" = whiskermenu ]; then
  xfconf-query -c xfce4-panel -p /plugins/plugin-1/show-command-lockscreen -n -t bool -s true
  xfconf-query -c xfce4-panel -p /plugins/plugin-1/command-lockscreen -n -t string -s "linx-phone lock"
fi
APPS=/usr/local/share/applications
mkdir -p "$APPS"
entry() {
  [ -f "$APPS/$1.desktop" ] && return
  printf '[Desktop Entry]\nType=Application\nName=%s\nComment=%s\nExec=%s\nIcon=%s\nCategories=%s\n' \
    "$2" "$3" "$4" "$5" "$6" >"$APPS/$1.desktop"
}
entry linx-screenshot "Screenshot" "Save the desktop to the phone's Pictures/Screenshots" \
  "linx-phone screenshot" applets-screenshooter Utility
entry linx-lock "Lock screen" "Lock the desktop until the phone's PIN or fingerprint" \
  "linx-phone lock" system-lock-screen System
entry linx-quick "Quick settings" "Wi-Fi, Bluetooth, torch, brightness and night light" \
  "linx-phone quick" preferences-system Settings
# Display, in the Settings app: the app sizes the screen, so its settings
# (size, full screen, performance) are where this goes (linx-apps.py hides
# XFCE's own). Rewritten each time, so a change here reaches it.
rm -f "$APPS/linx-display.desktop"
entry linx-display "Display" "Screen size, full screen and performance, in Mobuntu's settings" \
  "linx-phone mobuntu" org.xfce.settings.display \
  "Settings;DesktopSettings;X-XFCE-SettingsDialog;X-XFCE-HardwareSettings;"

# ------------------------------------------------------------ the layout
# How the bars are laid out, as chosen in the app (Settings → Appearance),
# which writes /tmp/linx-desktop-layout:
#   mobuntu  one bar at the bottom: the app grid, the dock, status, clock
#   windows  one bar at the bottom: Start, a few launchers, the open windows
#            with their names, status, clock
#   ubuntu   a bar at the top (the clock in the middle, status on the
#            right) and a dock at the bottom, centred, the app grid last
# The items are the ones set up above; a layout only says which go in which
# bar and where the bars are. The panel follows at its next start, or at
# once with --restart.
LAYOUT="$(cat /tmp/linx-desktop-layout 2>/dev/null)"
case "$LAYOUT" in windows | ubuntu) ;; *) LAYOUT=mobuntu ;; esac
have() { [ -n "$(get /plugins/plugin-$1)" ]; }
ids() {
  local out="" id
  for id in "$@"; do have "$id" && out="$out -t int -s $id"; done
  echo "$out"
}
panel_props() {
  local P=/panels/panel-$1
  xfconf-query -c xfce4-panel -p "$P/position" -n -t string -s "$2"
  xfconf-query -c xfce4-panel -p "$P/length" -n -t uint -s "$3"
  xfconf-query -c xfce4-panel -p "$P/length-adjust" -n -t bool -s "$4"
  xfconf-query -c xfce4-panel -p "$P/size" -n -t uint -s "$5"
  xfconf-query -c xfce4-panel -p "$P/position-locked" -n -t bool -s true
  xfconf-query -c xfce4-panel -p "$P/icon-size" -n -t uint -s 0
}
separator() {
  [ "$(get /plugins/plugin-$1)" = separator ] && return
  xfconf-query -c xfce4-panel -p "/plugins/plugin-$1" -n -t string -s separator
  xfconf-query -c xfce4-panel -p "/plugins/plugin-$1/expand" -n -t bool -s true
  xfconf-query -c xfce4-panel -p "/plugins/plugin-$1/style" -n -t uint -s 0
}
MARK_LAYOUT="$HOME/.config/xfce4/.linx-layout"
if [ "$(cat "$MARK_LAYOUT" 2>/dev/null)" != "$LAYOUT" ]; then
  # The status items, in their order (some may not be installed).
  STATUS="7 12 13 14 15"
  MENU_ICON=view-app-grid-symbolic
  CLOCK_FORMAT="%-I:%M %p"
  case "$LAYOUT" in
    windows)
      MENU_ICON=start-here
      # shellcheck disable=SC2046
      panel_props 1 "p=8;x=0;y=0" 100 false 40
      xfconf-query -c xfce4-panel -p /plugins/plugin-5/show-labels -n -t bool -s true
      # The launchers with the apps' own entries (and so their Yaru icons).
      launcher() {
        [ -f "/usr/share/applications/$2.desktop" ] || return
        rm -rf "$HOME/.config/xfce4/panel/launcher-$1"
        xfconf-query -c xfce4-panel -p "/plugins/plugin-$1/items" -n --force-array -t string -s "$2.desktop"
      }
      launcher 10 firefox
      launcher 2 xfce4-terminal
      launcher 3 thunar
      # shellcheck disable=SC2086,SC2046
      xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array $(ids 1 10 2 3 4 5 6 $STATUS 8 9)
      xfconf-query -c xfce4-panel -p /panels -n --force-array -t int -s 1
      ;;
    ubuntu)
      separator 20
      separator 21
      CLOCK_FORMAT="%a %-d %b  %-I:%M %p"
      panel_props 2 "p=6;x=0;y=0" 100 false 30
      xfconf-query -c xfce4-panel -p /panels/panel-2/dark-mode -n -t bool -s true
      # shellcheck disable=SC2086,SC2046
      xfconf-query -c xfce4-panel -p /panels/panel-2/plugin-ids -n --force-array $(ids 20 8 21 $STATUS 9)
      panel_props 1 "p=10;x=0;y=0" 1 true 48
      if have 11; then
        # shellcheck disable=SC2046
        xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array $(ids 11 1)
      else
        # shellcheck disable=SC2046
        xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array $(ids 10 2 3 5 1)
      fi
      xfconf-query -c xfce4-panel -p /panels -n --force-array -t int -s 1 -t int -s 2
      ;;
    *)
      panel_props 1 "p=8;x=0;y=0" 100 false 40
      if have 11 && [ "$(get /plugins/plugin-11)" = docklike ]; then
        # shellcheck disable=SC2086,SC2046
        xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array $(ids 1 11 6 $STATUS 8 9)
      else
        # shellcheck disable=SC2086,SC2046
        xfconf-query -c xfce4-panel -p /panels/panel-1/plugin-ids -n --force-array $(ids 1 10 2 3 4 5 6 $STATUS 8 9)
      fi
      xfconf-query -c xfce4-panel -p /panels -n --force-array -t int -s 1
      ;;
  esac
  [ "$(get /plugins/plugin-1)" = whiskermenu ] &&
    xfconf-query -c xfce4-panel -p /plugins/plugin-1/button-icon -n -t string -s "$MENU_ICON"
  xfconf-query -c xfce4-panel -p /plugins/plugin-8/digital-time-format -n -t string -s "$CLOCK_FORMAT"
  mkdir -p "$(dirname "$MARK_LAYOUT")"
  echo "$LAYOUT" >"$MARK_LAYOUT"
  CHANGED=1
fi

if [ "$CHANGED" = 1 ] && [ "${1:-}" = --restart ] && pgrep -x xfce4-panel >/dev/null; then
  restart_panel
fi
