#!/bin/bash
# The panel's app menu. Runs inside the container, in the desktop session.
#
#   linx-panel.sh             set it up in xfconf (before the panel starts)
#   linx-panel.sh --restart   and restart a running panel to pick it up
#
# Whisker Menu once linx-setup.sh has installed it: a search field, the
# favourites and every app in one list, with nothing hidden two levels down
# in categories. XFCE's own categories menu (the panel layout's default)
# until then.
set -u
export DISPLAY="${DISPLAY:-:${LINX_DISPLAY:-99}}"
if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ] && [ -s /tmp/linx-session-bus ]; then
  DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/linx-session-bus)"
  export DBUS_SESSION_BUS_ADDRESS
fi
[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || exit 0
command -v xfconf-query >/dev/null 2>&1 || exit 0
ls /usr/lib/*/xfce4/panel/plugins/libwhiskermenu.so >/dev/null 2>&1 || exit 0

# Already done (desktop-start.sh puts the stock layout back when it changes).
[ "$(xfconf-query -c xfce4-panel -p /plugins/plugin-1 2>/dev/null)" = whiskermenu ] && exit 0

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

if [ "${1:-}" = --restart ] && pgrep -x xfce4-panel >/dev/null; then
  xfce4-panel -r
fi
