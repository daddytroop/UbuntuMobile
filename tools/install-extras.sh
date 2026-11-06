#!/bin/bash
# The first-run pack: what makes the desktop a complete one, installed the
# first time it comes up. Runs inside the container, as root.
#
#   Firefox                      the browser (install-firefox.sh)
#   pulseaudio                   sound, played through the phone (linx-audio.sh)
#   yaru-theme-gtk/-icon, fonts-ubuntu   Ubuntu's own look (linx-look.sh)
#
# They are not in the image to keep the APK small, and Firefox cannot be
# (Ubuntu's package is a snap). Prints EXTRAS_READY when everything is in.
set -u
STATUS=0

/usr/local/lib/linx/install-firefox.sh || STATUS=1

/usr/local/lib/linx/apt-install.sh pulseaudio yaru-theme-gtk yaru-theme-icon fonts-ubuntu \
  || STATUS=1

if [ "$STATUS" -eq 0 ]; then
  echo "EXTRAS_READY"
else
  echo "EXTRAS_FAILED"
fi
exit "$STATUS"
