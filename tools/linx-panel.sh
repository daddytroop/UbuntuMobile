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

if [ "$CHANGED" = 1 ] && [ "${1:-}" = --restart ] && pgrep -x xfce4-panel >/dev/null; then
  xfce4-panel -r
fi
