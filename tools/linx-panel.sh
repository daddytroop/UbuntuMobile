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

plugin_installed() { ls /usr/lib/*/xfce4/panel/plugins/"lib$1.so" >/dev/null 2>&1; }
get() { xfconf-query -c xfce4-panel -p "$1" 2>/dev/null; }
CHANGED=0

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
    xfconf-query -c xfce4-panel -p "$P/update-period" -n -t int -s 3000
    xfconf-query -c xfce4-panel -p "$P/enable-single-row" -n -t bool -s true
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

if [ "$CHANGED" = 1 ] && [ "${1:-}" = --restart ] && pgrep -x xfce4-panel >/dev/null; then
  xfce4-panel -r
fi
