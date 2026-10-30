#!/bin/bash
# Start the X display + RFB export. Runs inside the Ubuntu container.
#
#   LINX_DISPLAY=99 LINX_RFB_PORT=5901 LINX_GEOMETRY=1024x600 display-start.sh
#
# Two backends:
#
#   xtigervnc (default)
#       TigerVNC's Xvnc: an X server and an RFB export in a single process.
#
#   x11vnc
#       Xvfb for the X server plus x11vnc, which polls the framebuffer
#       instead of relying on the X server's damage notifications. Use this
#       when the server does not push updates on its own.
#
# Prints DISPLAY_READY once the RFB port accepts connections.
set -u

DISPLAY_NUM="${LINX_DISPLAY:-99}"
RFB_PORT="${LINX_RFB_PORT:-5901}"
GEOMETRY="${LINX_GEOMETRY:-1024x600}"
DEPTH="${LINX_DEPTH:-24}"
MODE="${LINX_DISPLAY_MODE:-xtigervnc}"

export DISPLAY=":$DISPLAY_NUM"

# X needs a writable socket directory.
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix
rm -f "/tmp/.X${DISPLAY_NUM}-lock"

# x11vnc refuses to start when it sees a Wayland session, so make sure the
# guest environment never looks like one.
unset WAYLAND_DISPLAY
unset XDG_SESSION_TYPE

if [ -e "/tmp/.X${DISPLAY_NUM}-lock" ]; then
  echo "DISPLAY_ALREADY_RUNNING"
  exit 0
fi

port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3>&- && return 0
  return 1
}

start_xtigervnc() {
  # Ubuntu ships the binary as Xtigervnc; the Xvnc alias may not exist.
  local bin
  bin="$(command -v Xvnc || command -v Xtigervnc || echo /usr/bin/Xtigervnc)"
  [ -x "$bin" ] || return 1
  "$bin" ":$DISPLAY_NUM" \
    -geometry "$GEOMETRY" \
    -depth "$DEPTH" \
    -localhost \
    -SecurityTypes None \
    -rfbport "$RFB_PORT" \
    -AlwaysShared \
    -nolisten tcp \
    >/tmp/linx-xtigervnc.log 2>&1 &
  return 0
}

start_x11vnc() {
  local xvfb
  xvfb="$(command -v Xvfb || echo /usr/bin/Xvfb)"
  [ -x "$xvfb" ] || return 1
  "$xvfb" ":$DISPLAY_NUM" -screen 0 "${GEOMETRY}x${DEPTH}" -nolisten tcp -ac \
    >/tmp/linx-xvfb.log 2>&1 &
  sleep 3
  command -v x11vnc >/dev/null 2>&1 || return 1
  x11vnc -rfbport "$RFB_PORT" -rfbaddr 127.0.0.1 -localhost -forever -shared \
          -nopw -noxdamage -quiet -prefer hextile \
          >/tmp/linx-x11vnc.log 2>&1 &
  return 0
}

case "$MODE" in
  x11vnc)
    start_x11vnc || { echo "DISPLAY_START_FAILED"; exit 1; }
    ;;
  *)
    start_xtigervnc || { echo "DISPLAY_START_FAILED"; exit 1; }
    # If the server did not open the RFB port, fall back to Xvfb + x11vnc.
    sleep 4
    if ! port_open "$RFB_PORT"; then
      echo "falling back to Xvfb + x11vnc" >>/tmp/linx-display.log
      start_x11vnc
    fi
    ;;
esac

# Wait for the RFB port so the caller knows the display is ready.
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if port_open "$RFB_PORT"; then
    # Start a lightweight XFCE desktop inside the container. The components
    # run as children of the persistent proot process so they stay alive
    # as long as the display is wanted.
    export XDG_RUNTIME_DIR="/tmp/runtime-root"
    export XDG_CURRENT_DESKTOP="XFCE"
    export DESKTOP_SESSION="xfce"
    export XDG_CONFIG_DIRS="/etc/xdg/xdg-xfce4:/etc/xdg"
    mkdir -p "$XDG_RUNTIME_DIR"
    # XFCE's panel and window manager need a session D-Bus. dbus-run-session
    # starts one, runs the desktop components under it, and keeps the bus
    # alive as long as the wrapper sleeps.
    if command -v dbus-run-session >/dev/null 2>&1; then
      dbus-run-session -- bash -c '
        xfwm4 --compositor=off >/tmp/linx-xfwm4.log 2>&1 &
        xfce4-panel >/tmp/linx-panel.log 2>&1 &
        xfdesktop >/tmp/linx-desktop.log 2>&1 &
        thunar --daemon >/tmp/linx-thunar.log 2>&1 &
        exec sleep infinity
      ' >/tmp/linx-session.log 2>&1 &
    else
      # Fallback for the unlikely case dbus-run-session is missing.
      xfwm4 --compositor=off >/tmp/linx-xfwm4.log 2>&1 &
      xfce4-panel >/tmp/linx-panel.log 2>&1 &
      xfdesktop >/tmp/linx-desktop.log 2>&1 &
      thunar --daemon >/tmp/linx-thunar.log 2>&1 &
    fi
    echo "DISPLAY_READY"
    exit 0
  fi
  sleep 1
done
echo "DISPLAY_PORT_NOT_OPEN"
exit 1
