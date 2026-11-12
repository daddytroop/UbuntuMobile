#!/bin/bash
# Updates for everything installed, Ubuntu's packages and Flathub's apps
# alike. Runs inside the container, as root.
#
#   linx-updates.sh --check   print one line per update:
#                             "deb|package|new version" or "flatpak|app id|"
#   linx-updates.sh --apply   install them all; prints UPDATES_DONE, or
#                             UPDATES_FAILED and the reason
#
# With LINX_APT_STATUS set, apt's progress lines and Flatpak's percentages
# go to that file, for the app's progress bar (as in linx-setup.sh).
set -u
export DEBIAN_FRONTEND=noninteractive
# One install at a time (apt-install.sh takes the same lock): applying
# updates waits for its turn; checking does not, and lists what is known.
# Whatever this starts gets the lock closed (9>&-), so nothing keeps it.
exec 9>/tmp/linx-apt.lock
BUSY=0
if [ "${1:-}" = --apply ]; then
  flock -w 3600 9 || { echo "UPDATES_FAILED another install is still running"; exit 1; }
else
  flock -n 9 || BUSY=1
fi
STAMP=/var/lib/apt/lists/.linx-updated

check() {
  [ "$BUSY" = 1 ] || { apt-get update -q >/dev/null 2>&1 9>&- && touch "$STAMP"; }
  apt list --upgradable 2>/dev/null | awk -F'[/ ]' 'NR > 1 && $1 != "" {print "deb|" $1 "|" $3}'
  if command -v flatpak >/dev/null 2>&1 && flatpak remotes 2>/dev/null | grep -q flathub; then
    flatpak remote-ls --updates --columns=application 2>/dev/null | awk 'NF {print "flatpak|" $1 "|"}'
  fi
}

apply() {
  local failed=""
  STATUS_OPT=()
  if [ -n "${LINX_APT_STATUS:-}" ]; then
    STATUS_OPT=(-o APT::Status-Fd=3)
    exec 3>>"$LINX_APT_STATUS"
  fi
  # Keep the configuration files people changed; take new ones otherwise.
  if ! apt-get -y -q "${STATUS_OPT[@]}" \
      -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
      upgrade >/tmp/linx-updates.log 2>&1 9>&-; then
    failed="$(grep -E '^E: ' /tmp/linx-updates.log | tail -n 1)"
    [ -n "$failed" ] || failed="Ubuntu's packages did not update"
  fi
  if command -v flatpak >/dev/null 2>&1 && flatpak remotes 2>/dev/null | grep -q flathub; then
    # Flatpak prints how far it is only to a terminal (script gives it one).
    COLUMNS=100 script -qfec "flatpak update -y" /dev/null 2>&1 9>&- \
      | tee -a /tmp/linx-updates.log | tr '\r' '\n' \
      | grep --line-buffered -o '[0-9]\+%' | sed -u 's/^/flatpak:/' >>"${LINX_APT_STATUS:-/dev/null}"
    if [ "${PIPESTATUS[0]}" != 0 ] && [ -z "$failed" ]; then
      failed="Flathub's apps did not update"
    fi
  fi
  # New menu entries get the same clean-up as the image's.
  python3 /usr/local/lib/linx/linx-apps.py --tidy >/dev/null 2>&1 || true
  if [ -z "$failed" ]; then
    echo UPDATES_DONE
  else
    echo "UPDATES_FAILED $failed"
    return 1
  fi
}

case "${1:-}" in
  --check) check ;;
  --apply) apply ;;
  *) echo "usage: linx-updates.sh --check | --apply" >&2; exit 2 ;;
esac
