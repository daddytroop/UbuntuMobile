#!/bin/bash
# Start the X display + RFB export. Runs inside the Ubuntu container.
#
#   LINX_DISPLAY=99 LINX_RFB_PORT=5901 LINX_GEOMETRY=1024x600 display-start.sh
#
# The backend is TigerVNC's Xvnc, with Xvfb plus x11vnc as the fallback:
#
#   * Xvnc is the X server and the RFB server in one process, and it can
#     change the size of the screen while the desktop runs (the client asks
#     with SetDesktopSize). That is what lets the desktop follow the phone
#     when it rotates or is docked to a monitor.
#   * It was once dropped because windows came through black. That was the
#     app's Hextile decoder, not Xvnc: it lost every tile with detail in
#     it, which is all of the drawn content.
#   * Xvfb cannot change size (its RandR only knows the starting size), so
#     with the fallback the desktop keeps the shape it started with. x11vnc
#     polls the framebuffer with XGetImage and pushes it, which is the same
#     approach Termux:X11 uses.
#
# Prints DISPLAY_READY once the RFB port accepts connections.
set -u

DISPLAY_NUM="${LINX_DISPLAY:-99}"
RFB_PORT="${LINX_RFB_PORT:-5901}"
GEOMETRY="${LINX_GEOMETRY:-1024x600}"
DEPTH="${LINX_DEPTH:-24}"
MODE="${LINX_DISPLAY_MODE:-xvnc}"

export DISPLAY=":$DISPLAY_NUM"

# x11vnc refuses to start when it sees a Wayland session, so make sure the
# guest environment never looks like one.
unset WAYLAND_DISPLAY
unset XDG_SESSION_TYPE

port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3>&- && return 0
  return 1
}

# If the RFB port is already open the display is up and there is nothing to
# do.
if port_open "$RFB_PORT"; then
  echo "DISPLAY_ALREADY_RUNNING"
  exit 0
fi

# X needs a writable socket directory. A socket left behind by an earlier
# run stops the server from starting ("server already running"), so it is
# removed along with the lock file.
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}"
# The desktop session writes its bus address when it starts; one left from
# an earlier run points at a bus that is gone.
rm -f /tmp/linx-session-bus
# The starting scale. display-scale.sh replaces it if the app changes its
# mind while the desktop is still starting.
echo "${LINX_UI_SCALE:-2}" >/tmp/linx-ui-scale

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
    -ac \
    -dpi 96 \
    -desktop Ubuntu \
    -CompareFB 0 \
    >/tmp/linx-xtigervnc.log 2>&1 &
  return 0
}

wait_for_port() {
  for _ in $(seq 1 "$2"); do
    port_open "$1" && return 0
    sleep 1
  done
  return 1
}

start_x11vnc() {
  local xvfb
  xvfb="$(command -v Xvfb || echo /usr/bin/Xvfb)"
  [ -x "$xvfb" ] || return 1
  # MIT-SHM has to stay ENABLED. A client that draws into its window does
  # so by writing straight into a shared memory segment the X server
  # exports from /dev/shm, and that is the only path that works inside
  # proot: the fallback (XPutImage, which sends the pixels through the
  # socket) loses them. Every GTK application draws that way, so without
  # shared memory the panel, the file manager and every Flatpak would
  # come up as invisible windows.
  "$xvfb" ":$DISPLAY_NUM" -screen 0 "${GEOMETRY}x${DEPTH}" -nolisten tcp -ac \
    >/tmp/linx-xvfb.log 2>&1 &
  sleep 3
  command -v x11vnc >/dev/null 2>&1 || return 1
  # NOTE: -rfbaddr and -prefer are not options in this version of x11vnc
  # (it aborts with "unrecognized option"), so the loopback restriction
  # comes from -localhost and the encoding is left at the default.
  x11vnc -rfbport "$RFB_PORT" -localhost -forever -shared -nopw -noxdamage \
          -quiet \
          >/tmp/linx-x11vnc.log 2>&1 &
  return 0
}

start_x11vnc_shm() {
  # The same stack, but with the shared memory extension left off. It is
  # only used as a last resort: without it the applications cannot draw.
  local xvfb
  xvfb="$(command -v Xvfb || echo /usr/bin/Xvfb)"
  [ -x "$xvfb" ] || return 1
  "$xvfb" ":$DISPLAY_NUM" -screen 0 "${GEOMETRY}x${DEPTH}" -nolisten tcp -ac \
    -extension MIT-SHM \
    >/tmp/linx-xvfb.log 2>&1 &
  sleep 3
  command -v x11vnc >/dev/null 2>&1 || return 1
  x11vnc -rfbport "$RFB_PORT" -localhost -forever -shared -nopw -noxdamage \
          -noshm -quiet \
          >/tmp/linx-x11vnc.log 2>&1 &
  return 0
}

start_x11vnc_with_fallback() {
    start_x11vnc || return 1
    # x11vnc takes several seconds to come up on a phone, so give it time
    # before deciding it failed. If it really did not open the RFB port,
    # stop what was started and try again without the shared memory
    # extension - starting a second server next to the first one only
    # fails with "server already running".
    if ! wait_for_port "$RFB_PORT" 20; then
      echo "retrying without MIT-SHM" >>/tmp/linx-display.log
      pkill -x x11vnc 2>/dev/null || true
      pkill -x Xvfb 2>/dev/null || true
      sleep 1
      rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}"
      start_x11vnc_shm
    fi
    return 0
}

case "$MODE" in
  x11vnc)
    start_x11vnc_with_fallback || { echo "DISPLAY_START_FAILED"; exit 1; }
    ;;
  *)
    # Xvnc, unless it is missing or does not open the port; then the
    # fixed-size stack.
    if start_xtigervnc && wait_for_port "$RFB_PORT" 20; then
      echo "the display is Xvnc" >>/tmp/linx-display.log
    else
      echo "Xvnc did not start; using Xvfb and x11vnc" >>/tmp/linx-display.log
      pkill -f Xtigervnc 2>/dev/null || true
      pkill -x Xvnc 2>/dev/null || true
      sleep 1
      rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}"
      start_x11vnc_with_fallback || { echo "DISPLAY_START_FAILED"; exit 1; }
    fi
    ;;
esac

# Wait for the RFB port so the caller knows the display is ready.
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if port_open "$RFB_PORT"; then
    # Start the desktop inside the container. The components run as children
    # of the persistent proot process so they stay alive as long as the
    # display is wanted.
    export XDG_RUNTIME_DIR="/tmp/runtime-root"
    export XDG_CURRENT_DESKTOP="XFCE"
    export DESKTOP_SESSION="xfce"
    export XDG_CONFIG_DIRS="/etc/xdg/xdg-xfce4:/etc/xdg"
    mkdir -p "$XDG_RUNTIME_DIR"

    # GTK loads images through glycin, whose bwrap sandbox cannot work
    # under proot. The stand-in makes it load them unsandboxed instead; see
    # nosandbox-bwrap.
    export PATH="/usr/local/lib/linx/nosandbox:$PATH"
    # Flatpak finds bwrap on PATH too; keep it on the real one.
    export FLATPAK_BWRAP=/usr/bin/bwrap

    # D-Bus refuses to start a session bus without a machine id, and the
    # rootfs is built without one (systemd would normally create it).
    if [ ! -s /etc/machine-id ]; then
      dbus-uuidgen >/etc/machine-id 2>/dev/null || \
        cat /proc/sys/kernel/random/uuid | tr -d '-' >/etc/machine-id
    fi
    mkdir -p /var/lib/dbus
    [ -s /var/lib/dbus/machine-id ] || cp /etc/machine-id /var/lib/dbus/machine-id

    # Without preferred applications XFCE asks "choose your terminal" the
    # first time anything opens one. These are the ones the image ships.
    HELPERS="$HOME/.config/xfce4/helpers.rc"
    if [ ! -s "$HELPERS" ]; then
      mkdir -p "$(dirname "$HELPERS")"
      {
        echo "TerminalEmulator=xfce4-terminal"
        echo "FileManager=thunar"
      } >"$HELPERS"
    fi

    # The X screen is sized like a normal desktop screen (the phone's
    # aspect ratio, short side 720 pixels), not like the phone's display.
    # The desktop is laid out in pixels, so the panel and the fonts have to
    # be the size they are on a desktop for them to be tappable.
    #
    # On the phone every GTK widget (and the fonts with it) is doubled so
    # the applications use a phone-sized logical screen; on a monitor it
    # is not. desktop-start.sh sets that up through XSETTINGS, which the
    # running applications follow, so it can change when the phone is
    # docked (display-scale.sh). QT_SCALE_FACTOR does the same for Qt
    # applications, but only for those started afterwards.
    SCALE="$(cat /tmp/linx-ui-scale 2>/dev/null || echo "${LINX_UI_SCALE:-2}")"
    export QT_SCALE_FACTOR="$SCALE"
    export QT_ENABLE_HIGHDPI_SCALING=1

    # Normalise the font DPI for the X11-only applications that ignore
    # the XSETTINGS scale. Antialiasing is greyscale: the picture is scaled
    # on its way to the screen, and coloured subpixel edges would fringe.
    if command -v xrdb >/dev/null 2>&1; then
      {
        echo "Xft.dpi: 96"
        echo "Xft.antialias: 1"
        echo "Xft.rgba: none"
        echo "Xft.hinting: true"
        echo "Xft.hintstyle: hintslight"
      } | xrdb -merge 2>/dev/null || true
    fi

    # The desktop needs a session D-Bus. dbus-run-session starts one and
    # runs the desktop under it. The desktop script never returns, so the
    # bus stays up for as long as the display is wanted.
    DESKTOP="${LINX_DESKTOP:-xfce}"
    if command -v dbus-run-session >/dev/null 2>&1; then
      dbus-run-session -- /usr/local/lib/linx/desktop-start.sh "$DESKTOP" \
        >/tmp/linx-session.log 2>&1 &
    else
      /usr/local/lib/linx/desktop-start.sh "$DESKTOP" \
        >/tmp/linx-session.log 2>&1 &
    fi
    echo "DISPLAY_READY"
    exit 0
  fi
  sleep 1
done
echo "DISPLAY_PORT_NOT_OPEN"
exit 1
