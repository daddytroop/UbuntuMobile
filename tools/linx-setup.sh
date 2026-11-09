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
# How far each step's download and install are, for the app's progress bar:
# apt's status lines ("dlstatus:3:42.5:...", "pmstatus:...") and Flatpak's
# percentages ("flatpak:42%").
export LINX_APT_STATUS=/tmp/linx-setup-progress
step() { N=$((N + 1)); : >"$LINX_APT_STATUS"; echo "STEP $N/$TOTAL $1"; }

step "Sound and the Ubuntu look"
/usr/local/lib/linx/apt-install.sh pulseaudio yaru-theme-gtk yaru-theme-icon fonts-ubuntu \
  xfce4-whiskermenu-plugin \
  >/tmp/linx-setup-base.log 2>&1 || FAILED="$FAILED base"

if [ "$LINX_BROWSER" = 1 ]; then
  step "Firefox"
  /usr/local/lib/linx/install-firefox.sh >/tmp/linx-setup-browser.log 2>&1 || FAILED="$FAILED browser"
fi

if [ -n "$LINX_APPS" ]; then
  step "Apps"
  # shellcheck disable=SC2086
  /usr/local/lib/linx/apt-install.sh $LINX_APPS >/tmp/linx-setup-apps.log 2>&1 || FAILED="$FAILED apps"
fi

if [ "$LINX_FLATHUB" = 1 ]; then
  step "Flathub"
  /usr/local/lib/linx/linx-flatpak.sh >/tmp/linx-setup-flatpak.log 2>&1 || FAILED="$FAILED flathub"
  if [ -n "$LINX_FLATPAKS" ]; then
    step "Apps from Flathub"
    # shellcheck disable=SC2086
    flatpak install -y --noninteractive flathub $LINX_FLATPAKS 2>&1 \
      | tee /tmp/linx-setup-flatpaks.log | tr '\r' '\n' \
      | grep --line-buffered -o '[0-9]\+%' | sed -u 's/^/flatpak:/' >>"$LINX_APT_STATUS"
    [ "${PIPESTATUS[0]}" = 0 ] || FAILED="$FAILED flatpaks"
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
