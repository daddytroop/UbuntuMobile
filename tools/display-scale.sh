#!/bin/bash
# Change the UI scale of the running desktop. Runs inside the container.
#
#   display-scale.sh 2     phone: every GTK widget and font doubled
#   display-scale.sh 1     monitor: the normal desktop size
#
# The running GTK applications follow at once (desktop-start.sh serves the
# scale through XSETTINGS). Qt applications only read QT_SCALE_FACTOR when
# they start, so the app passes the scale from /tmp/linx-ui-scale to the
# ones it launches.
set -u

SCALE="${1:-2}"
case "$SCALE" in
  1|2|3) ;;
  *) echo "usage: display-scale.sh 1|2|3" >&2; exit 2 ;;
esac

export DISPLAY="${DISPLAY:-:${LINX_DISPLAY:-99}}"
# xfconf lives on the desktop session's bus, not on one of our own.
if [ -s /tmp/linx-session-bus ]; then
  DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/linx-session-bus)"
  export DBUS_SESSION_BUS_ADDRESS
fi

echo "$SCALE" >/tmp/linx-ui-scale
# Before the session is up there is no bus to talk to; the session reads
# /tmp/linx-ui-scale when it starts instead.
if [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] && command -v xfconf-query >/dev/null 2>&1; then
  xfconf-query -c xsettings -p /Gdk/WindowScalingFactor -n -t int -s "$SCALE"
  # The window borders come in sizes; xfwm4 follows the change.
  /usr/local/lib/linx/linx-look.sh >/dev/null 2>&1
fi
# Back on the phone, windows sized for the monitor would hang off the
# screen; they fill it instead, as new ones do there.
if [ "$SCALE" -ge 2 ] && command -v python3 >/dev/null 2>&1; then
  python3 /usr/local/lib/linx/linx-windows.py --all 2>/dev/null
fi
echo "SCALE=$SCALE"
