#!/bin/bash
# The performance level the app chose (Settings → Performance), in Linux.
# The app writes it to /tmp/linx-performance: basic, balanced or fast.
#
#   linx-performance.sh           apply it to the running desktop
#   linx-performance.sh --start   only what desktop-start.sh does not
#                                 (it leaves out the components itself)
#
#   basic     no desktop icons (xfdesktop; the wallpaper stays), no file
#             manager in the background, no printing, no animations
#   balanced  everything, no animations
#   fast      everything, with animations
set -u
LEVEL="$(cat /tmp/linx-performance 2>/dev/null)"
case "$LEVEL" in basic | balanced | fast) ;; *) LEVEL=balanced ;; esac
export DISPLAY="${DISPLAY:-:${LINX_DISPLAY:-99}}"
if [ "${DBUS_SESSION_BUS_ADDRESS:-disabled:}" = disabled: ] && [ -s /tmp/linx-session-bus ]; then
  DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/linx-session-bus)"
  export DBUS_SESSION_BUS_ADDRESS
fi

# Animations: drawn without a GPU, they cost more than they give.
if command -v xfconf-query >/dev/null 2>&1; then
  [ "$LEVEL" = fast ] && on=true || on=false
  xfconf-query -c xsettings -p /Net/EnableAnimations -n -t bool -s "$on" 2>/dev/null
fi
[ "${1:-}" = --start ] && exit 0

if [ "$LEVEL" = basic ]; then
  pkill -x xfdesktop 2>/dev/null
  thunar -q 2>/dev/null
  pkill -x cupsd 2>/dev/null
  # xfdesktop covered feh's wallpaper; draw it again.
  wall="$(cat /tmp/linx-wallpaper 2>/dev/null)"
  [ -f "$wall" ] || wall=/usr/share/xfce4/backdrops/xubuntu-wallpaper.png
  sleep 1
  feh --no-fehbg --bg-fill "$wall" >/dev/null 2>&1
else
  pgrep -x xfdesktop >/dev/null || setsid xfdesktop >/tmp/linx-desktop.log 2>&1 </dev/null &
  pgrep -f '^thunar --daemon' >/dev/null || setsid thunar --daemon >/tmp/linx-thunar.log 2>&1 </dev/null &
  if [ -f /etc/linx/setup.done ] && ! pgrep -x cupsd >/dev/null; then
    setsid bash /usr/local/lib/linx/linx-print.sh >/tmp/linx-print.log 2>&1 </dev/null &
  fi
fi
exit 0
