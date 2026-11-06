#!/bin/bash
# Start the desktop components inside the X display.
#
#   desktop-start.sh xfce     XFCE panel and file manager on Openbox
#   desktop-start.sh plain    Openbox + tint2 + feh (no GTK at all)
#
# The window manager is the important part. Without one the X server does
# not map the windows of the applications, so nothing is ever drawn - the
# applications sit there unmapped and the display stays black.
#
# XFCE's own window manager (xfwm4) does not take over the display inside
# the container: it creates its supporting window but never registers
# itself, so the applications wait for a window manager that never comes.
# Openbox does register, and it works.
#
# The panel is tint2 rather than the XFCE one for the same reason the
# window manager is Openbox: the GTK programs (XFCE's panel, Thunar,
# Mousepad) do not paint inside the container, while the plain X11 ones
# (tint2, feh, xterm, xfe) do.
set -u

DESKTOP="${1:-xfce}"

wm_is_running() {
  # A window manager that has taken over sets this on the root window.
  xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && return 0
  # Older window managers only set the ICCCM property.
  xprop -root WM_STATE 2>/dev/null | grep -q ' = ' && return 0
  return 1
}

start_openbox() {
  openbox >/tmp/linx-openbox.log 2>&1 &
}

# The panel and the wallpaper.
start_panel() {
  xfce4-panel >/tmp/linx-panel.log 2>&1 &
  # The wallpaper. feh is a plain X11 program, so it draws where the GTK
  # ones cannot.
  feh --no-fehbg --bg-fill /usr/share/xfce4/backdrops/xubuntu-wallpaper.png \
      >/tmp/linx-feh.log 2>&1 &
}

# The fallback panel, for the case where the GTK one does not come up.
start_plain_panel() {
  tint2 -c /usr/local/share/linx/tint2rc >/tmp/linx-tint2.log 2>&1 &
  feh --no-fehbg --bg-fill /usr/share/xfce4/backdrops/xubuntu-wallpaper.png \
      >/tmp/linx-feh.log 2>&1 &
}

# The XFCE programs are started as well. They add the desktop icons and
# the file manager's menu integration.
start_xfce_extras() {
  xfdesktop >/tmp/linx-desktop.log 2>&1 &
  thunar --daemon >/tmp/linx-thunar.log 2>&1 &
}

# The UI scale (2 on the phone, 1 on a monitor) goes through XSETTINGS
# rather than GDK_SCALE: GTK applications follow an XSETTINGS change while
# they run, so the scale can change when the phone is docked without
# restarting anything. xfsettingsd is what serves XSETTINGS; the scale is
# stored in xfconf, where display-scale.sh changes it.
start_settings() {
  # display-scale.sh runs outside this session and needs its bus. From
  # here on it changes the scale through xfconf...
  echo "$DBUS_SESSION_BUS_ADDRESS" >/tmp/linx-session-bus
  # ...and until here it could only leave it in the file.
  SCALE="$(cat /tmp/linx-ui-scale 2>/dev/null || echo "${LINX_UI_SCALE:-2}")"
  if command -v xfsettingsd >/dev/null 2>&1 && command -v xfconf-query >/dev/null 2>&1; then
    xfconf-query -c xsettings -p /Gdk/WindowScalingFactor -n -t int -s "$SCALE"
    # The X server already says 96 DPI; doubling it as well would make
    # the text four times the size.
    xfconf-query -c xsettings -p /Xft/DPI -n -t int -s 96
    # A screen that changes size is not a new monitor: no dialog about it.
    xfconf-query -c displays -p /Notify -n -t int -s 0 2>/dev/null
    xfsettingsd --no-daemon >/tmp/linx-xfsettingsd.log 2>&1 &
    sleep 1
  else
    # No XSETTINGS daemon: a fixed scale is better than none.
    export GDK_SCALE="$SCALE"
    export GDK_DPI_SCALE=1
  fi
}

start_settings

case "$DESKTOP" in
  plain)
    start_openbox
    sleep 2
    start_panel
    ;;
  *)
    start_openbox
    # Give the window manager a moment and check that it took over.
    for _ in 1 2 3 4 5 6 7 8; do
      sleep 1
      wm_is_running && break
    done
    if ! wm_is_running; then
      echo "Openbox did not take over; trying XFCE's own window manager" \
        >>/tmp/linx-session.log
      xfwm4 --compositor=off >/tmp/linx-xfwm4.log 2>&1 &
      sleep 2
    fi
    if wm_is_running; then
      start_panel
      start_xfce_extras
    else
      echo "no window manager; falling back to the plain desktop" \
        >>/tmp/linx-session.log
      start_panel
    fi
    ;;
esac

# Stay alive. This script runs under dbus-run-session, and the session bus
# has to outlive the components: the GTK applications connect to it and
# exit when it disappears.
exec sleep infinity
