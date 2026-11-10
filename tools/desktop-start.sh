#!/bin/bash
# Start the desktop components inside the X display.
#
#   desktop-start.sh xfce     XFCE: xfwm4, the panel, the desktop, Thunar
#   desktop-start.sh plain    Openbox + tint2 + feh (no GTK at all)
#
# The window manager is the important part. Without one the X server does
# not map the windows of the applications, so nothing is ever drawn - the
# applications sit there unmapped and the display stays black.
#
# The window manager is XFCE's own, xfwm4: its themes come in 1x, 2x and 3x
# sizes, so the title bar buttons are big enough to tap on the phone and
# normal on a monitor. Openbox is the fallback; its buttons are fixed,
# tiny bitmaps. (Under the old Xvfb display xfwm4 never took over, which is
# why Openbox was first for a while. Under Xvnc it does.)
set -u

DESKTOP="${1:-xfce}"

# The panel layout ships with the app; it is installed over the user's once
# per version, so an update can change it. Bump this when it changes.
PANEL_VERSION=2

wm_is_running() {
  # A window manager that has taken over sets this on the root window.
  xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && return 0
  # Older window managers only set the ICCCM property.
  xprop -root WM_STATE 2>/dev/null | grep -q ' = ' && return 0
  return 1
}

wait_for_wm() {
  for _ in $(seq 1 "$1"); do
    sleep 1
    wm_is_running && return 0
  done
  return 1
}

start_xfwm4() {
  # No compositor: without a GPU it only costs CPU, and nothing needs it.
  xfwm4 --compositor=off >/tmp/linx-xfwm4.log 2>&1 &
}

start_openbox() {
  openbox >/tmp/linx-openbox.log 2>&1 &
}

install_panel_layout() {
  local conf="$HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
  local mark="$HOME/.config/xfce4/.linx-panel-version"
  [ -f /usr/local/share/linx/xfce4-panel.xml ] || return
  [ "$(cat "$mark" 2>/dev/null)" = "$PANEL_VERSION" ] && return
  mkdir -p "$conf"
  cp /usr/local/share/linx/xfce4-panel.xml "$conf/xfce4-panel.xml"
  # The old layout's launchers keep their own copies here.
  rm -rf "$HOME/.config/xfce4/panel"
  echo "$PANEL_VERSION" >"$mark"
}

# The panel and the wallpaper.
start_panel() {
  xfce4-panel >/tmp/linx-panel.log 2>&1 &
  # The wallpaper. feh is a plain X11 program, so it draws where the GTK
  # ones cannot.
  # The wallpaper chosen at setup (linx-look.sh says which).
  local wall
  wall="$(cat /tmp/linx-wallpaper 2>/dev/null)"
  [ -f "$wall" ] || wall=/usr/share/xfce4/backdrops/xubuntu-wallpaper.png
  feh --no-fehbg --bg-fill "$wall" >/tmp/linx-feh.log 2>&1 &
}

# The panel's battery and network items need the generic monitor plugin,
# which setup installs; a Linux set up before it existed gets it here, once,
# in the background (the panel then restarts with them).
add_status_plugin() {
  ls /usr/lib/*/xfce4/panel/plugins/libgenmon.so >/dev/null 2>&1 && return
  [ -f /var/lib/linx/no-genmon ] && return
  sleep 20
  if /usr/local/lib/linx/apt-install.sh xfce4-genmon-plugin >/tmp/linx-genmon.log 2>&1; then
    /usr/local/lib/linx/linx-panel.sh --restart >/dev/null 2>&1
  elif grep -q "Unable to locate" /tmp/linx-genmon.log; then
    mkdir -p /var/lib/linx && touch /var/lib/linx/no-genmon
  fi
}

# The XFCE programs are started as well. They add the desktop icons and
# the file manager's menu integration.
start_xfce_extras() {
  xfdesktop >/tmp/linx-desktop.log 2>&1 &
  thunar --daemon >/tmp/linx-thunar.log 2>&1 &
  # On the phone, application windows open maximized.
  python3 /usr/local/lib/linx/linx-windows.py >/tmp/linx-windows.log 2>&1 &
  # Apps' notifications reach the phone's notification shade too.
  python3 /usr/local/lib/linx/linx-notify.py >/tmp/linx-notify.log 2>&1 &
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
    set_xsettings() { xfconf-query -c xsettings -p "$1" -n -t "$2" -s "$3"; }
    set_xsettings /Gdk/WindowScalingFactor int "$SCALE"
    # The X server already says 96 DPI; doubling it as well would make
    # the text four times the size.
    set_xsettings /Xft/DPI int 96
    # Greyscale antialiasing: the picture is scaled on its way to the
    # screen, and coloured subpixel edges would turn into fringes.
    set_xsettings /Xft/Antialias int 1
    set_xsettings /Xft/Hinting int 1
    set_xsettings /Xft/HintStyle string hintslight
    set_xsettings /Xft/RGBA string none
    # A screen that changes size is not a new monitor: no dialog about it.
    xfconf-query -c displays -p /Notify -n -t int -s 0 2>/dev/null

    # The window manager: one workspace, and the usual buttons on the
    # right. The theme, the fonts and the icons are linx-look.sh's: Yaru
    # and the Ubuntu font once the first-run pack is in, and window
    # borders sized for the scale.
    /usr/local/lib/linx/linx-look.sh >/tmp/linx-look.log 2>&1
    xfconf-query -c xfwm4 -p /general/button_layout -n -t string -s "O|HMC"
    xfconf-query -c xfwm4 -p /general/workspace_count -n -t int -s 1
    xfconf-query -c xfwm4 -p /general/use_compositing -n -t bool -s false

    xfsettingsd --no-daemon >/tmp/linx-xfsettingsd.log 2>&1 &
    sleep 1
  else
    # No XSETTINGS daemon: a fixed scale is better than none.
    export GDK_SCALE="$SCALE"
    export GDK_DPI_SCALE=1
  fi
}

# Before anything reads xfconf, so the daemon loads the new layout.
install_panel_layout
start_settings

case "$DESKTOP" in
  plain)
    start_openbox
    sleep 2
    start_panel
    ;;
  *)
    start_xfwm4
    WM_PID=$!
    # xfwm4 loads its theme's images first, and on a phone that can take
    # twenty seconds. Wait for as long as it is still alive and starting:
    # killing it halfway through taking over leaves the screen claimed, and
    # Openbox then refuses with "a window manager is already running".
    for _ in $(seq 1 60); do
      wm_is_running && break
      kill -0 "$WM_PID" 2>/dev/null || break
      sleep 1
    done
    if ! wm_is_running; then
      echo "xfwm4 did not take over; using Openbox" >>/tmp/linx-session.log
      kill "$WM_PID" 2>/dev/null
      sleep 1
      openbox --replace >/tmp/linx-openbox.log 2>&1 &
      wait_for_wm 15 || echo "no window manager either way" >>/tmp/linx-session.log
    fi
    start_panel
    start_xfce_extras
    add_status_plugin &
    ;;
esac

# Stay alive. This script runs under dbus-run-session, and the session bus
# has to outlive the components: the GTK applications connect to it and
# exit when it disappears.
exec sleep infinity
