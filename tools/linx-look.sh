#!/bin/bash
# Apply the desktop's look. Runs inside the container, in or alongside the
# desktop session; the running applications follow at once (XSETTINGS).
#
# Ubuntu's own Yaru theme, icons and font once the first-run pack has
# installed them (install-extras.sh); GTK's Adwaita and Noto Sans until
# then. The window borders are the app's own theme, drawn by
# make-xfwm4-theme.py in the Yaru colours, in a 1x and a 2x size.
set -u
export DISPLAY="${DISPLAY:-:${LINX_DISPLAY:-99}}"
if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ] && [ -s /tmp/linx-session-bus ]; then
  DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/linx-session-bus)"
  export DBUS_SESSION_BUS_ADDRESS
fi
[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || exit 0
command -v xfconf-query >/dev/null 2>&1 || exit 0

set_xsettings() { xfconf-query -c xsettings -p "$1" -n -t "$2" -s "$3"; }

if [ -d /usr/share/themes/Yaru ]; then
  set_xsettings /Net/ThemeName string Yaru
else
  set_xsettings /Net/ThemeName string Adwaita
fi
if [ -d /usr/share/icons/Yaru ]; then
  set_xsettings /Net/IconThemeName string Yaru
else
  set_xsettings /Net/IconThemeName string Adwaita
fi
if fc-list 2>/dev/null | grep -q "Ubuntu-R\|Ubuntu Sans\|/ubuntu/"; then
  FONT="Ubuntu"
  MONO="Ubuntu Mono"
else
  FONT="Noto Sans"
  MONO="Noto Sans Mono"
fi
set_xsettings /Gtk/FontName string "$FONT 10"
set_xsettings /Gtk/MonospaceFontName string "$MONO 11"
xfconf-query -c xfwm4 -p /general/title_font -n -t string -s "$FONT Bold 10"

# The window borders: the app's theme when it has been drawn, sized for the
# current scale (display-scale.sh switches it).
python3 /usr/local/lib/linx/make-xfwm4-theme.py >/dev/null 2>&1
SCALE="$(cat /tmp/linx-ui-scale 2>/dev/null || echo 2)"
if [ -f /usr/share/themes/Linx/xfwm4/themerc ]; then
  THEME=Linx
  [ "$SCALE" -ge 2 ] && THEME=Linx-xhdpi
else
  THEME=Default
  [ "$SCALE" -ge 2 ] && THEME=Default-xhdpi
fi
xfconf-query -c xfwm4 -p /general/theme -n -t string -s "$THEME"
echo "LOOK=$THEME"
