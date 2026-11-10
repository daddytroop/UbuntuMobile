#!/bin/bash
# Set Linux up the way onboarding asked. Runs inside the container, as root.
#
# Onboarding (OnboardingScreen.kt) writes its choices to /etc/linx/setup.conf:
#
#   LINX_BROWSER=1                Firefox (install-firefox.sh)
#   LINX_APPS="gimp vlc ..."      Ubuntu packages
#   LINX_FLATHUB=1                Flatpak apps can run (linx-flatpak.sh)
#   LINX_FLATPAKS="org.x.Y ..."   apps from Flathub
#   LINX_DARK=0  LINX_ACCENT=orange   the look (linx-look.sh)
#
# Sound and Ubuntu's own look always come in: they are small and the desktop
# is not complete without them. Prints "STEP n/total what" as it goes, and
# SETUP_READY or SETUP_FAILED at the end. Running it again installs only
# what is missing.
set -u
CONF=/etc/linx/setup.conf
LINX_BROWSER=1
LINX_APPS=""
LINX_FLATHUB=0
LINX_FLATPAKS=""
[ -f "$CONF" ] && . "$CONF"

TOTAL=1
[ "$LINX_BROWSER" = 1 ] && TOTAL=$((TOTAL + 1))
[ -n "$LINX_APPS" ] && TOTAL=$((TOTAL + 1))
[ "$LINX_FLATHUB" = 1 ] && TOTAL=$((TOTAL + 1))
[ "$LINX_FLATHUB" = 1 ] && [ -n "$LINX_FLATPAKS" ] && TOTAL=$((TOTAL + 1))
N=0
FAILED=""
ERROR=/tmp/linx-setup-error
: >"$ERROR"
# How far each step's download and install are, for the app's progress bar:
# apt's status lines ("dlstatus:3:42.5:...", "pmstatus:...") and Flatpak's
# percentages ("flatpak:42%").
export LINX_APT_STATUS=/tmp/linx-setup-progress
STEP_NAME=""
step() { N=$((N + 1)); STEP_NAME="$1"; : >"$LINX_APT_STATUS"; echo "STEP $N/$TOTAL $1"; }

# A step failed: remember it, and say why in one line for the app (the
# first failure is the one shown): the last error the step's log has.
fail() {
  FAILED="$FAILED $1"
  [ -s "$ERROR" ] && return
  local why
  why="$(grep -aiE 'error|failed|E: |not enough|unable|could not' "$2" 2>/dev/null | tail -n 1)"
  [ -n "$why" ] || why="$(tail -n 1 "$2" 2>/dev/null)"
  printf '%s: %s\n' "$STEP_NAME" "$why" | cut -c1-300 >"$ERROR"
}

# Megabytes free for the container.
free_mb() { df -Pm / 2>/dev/null | awk 'NR == 2 {print $4}'; }

step "Sound and the Ubuntu look"
/usr/local/lib/linx/apt-install.sh pulseaudio yaru-theme-gtk yaru-theme-icon fonts-ubuntu \
  xfce4-whiskermenu-plugin xfce4-docklike-plugin xfce4-notifyd libnotify-bin \
  xfce4-genmon-plugin xfce4-pulseaudio-plugin \
  >/tmp/linx-setup-base.log 2>&1 || fail base /tmp/linx-setup-base.log

if [ "$LINX_BROWSER" = 1 ]; then
  step "Firefox"
  /usr/local/lib/linx/install-firefox.sh >/tmp/linx-setup-browser.log 2>&1 ||
    fail browser /tmp/linx-setup-browser.log
fi

if [ -n "$LINX_APPS" ]; then
  step "Apps"
  # shellcheck disable=SC2086
  /usr/local/lib/linx/apt-install.sh $LINX_APPS >/tmp/linx-setup-apps.log 2>&1 ||
    fail apps /tmp/linx-setup-apps.log
fi

if [ "$LINX_FLATHUB" = 1 ]; then
  step "Flathub"
  if /usr/local/lib/linx/linx-flatpak.sh >/tmp/linx-setup-flatpak.log 2>&1; then
    FLATHUB_OK=1
  else
    FLATHUB_OK=0
    fail flathub /tmp/linx-setup-flatpak.log
  fi
  if [ -n "$LINX_FLATPAKS" ] && [ "$FLATHUB_OK" = 1 ]; then
    step "Apps from Flathub"
    : >/tmp/linx-setup-flatpaks.log
    # The first app brings a runtime of a few hundred MB; say so plainly
    # rather than fail half-way through it.
    FREE="$(free_mb)"
    if [ -n "$FREE" ] && [ "$FREE" -lt 1200 ]; then
      echo "error: not enough space: ${FREE} MB free, Flathub apps need about 1.2 GB" >>/tmp/linx-setup-flatpaks.log
      fail flatpaks /tmp/linx-setup-flatpaks.log
    else
      # One at a time, so an app that is not built for this phone's
      # processor does not keep the others out. Flatpak prints how far
      # it is only to a terminal, so it gets one (script).
      for app in $LINX_FLATPAKS; do
        COLUMNS=100 script -qfec "flatpak install -y --or-update flathub $app" /dev/null 2>&1 \
          | tee -a /tmp/linx-setup-flatpaks.log | tr '\r' '\n' \
          | grep --line-buffered -o '[0-9]\+%' | sed -u 's/^/flatpak:/' >>"$LINX_APT_STATUS"
        [ "${PIPESTATUS[0]}" = 0 ] || fail "flatpak:$app" /tmp/linx-setup-flatpaks.log
      done
      # Run each once, doing nothing: Flatpak builds the app's library
      # cache (~/.var/app/<id>/.ld.so) the first time, so the first real
      # start is quicker. Not a failure if one will not run here.
      for app in $LINX_FLATPAKS; do
        timeout 300 flatpak run --command=true "$app" >>/tmp/linx-setup-flatpaks.log 2>&1 || true
      done
    fi
  fi
fi

if [ -z "$FAILED" ]; then
  mkdir -p /etc/linx
  cp "$CONF" /etc/linx/setup.done 2>/dev/null || touch /etc/linx/setup.done
  echo "SETUP_READY"
else
  echo "SETUP_FAILED$FAILED"
  exit 1
fi
