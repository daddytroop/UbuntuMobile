#!/bin/bash
# Install Ubuntu packages for the app's store. Runs inside the container.
#
#   apt-install.sh firefox             install (and say APT_DONE)
#   apt-install.sh --status pkg...     print "pkg installed|missing" lines
#   apt-install.sh --search words      print "pkg|summary" for matches
#
# The image ships without apt's package lists (they are large and go stale),
# so the first install fetches them; after that they are refreshed once a
# day at most.
set -u
export DEBIAN_FRONTEND=noninteractive
STAMP=/var/lib/apt/lists/.linx-updated

refresh_lists() {
  if [ -z "$(find "$STAMP" -mmin -1440 2>/dev/null)" ]; then
    apt-get update -q && touch "$STAMP"
  fi
}

case "${1:-}" in
  --status)
    shift
    for p in "$@"; do
      if dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "install ok installed"; then
        echo "$p installed"
      else
        echo "$p missing"
      fi
    done
    ;;
  --search)
    shift
    refresh_lists >/dev/null 2>&1
    # Applications only: packages that come with a menu entry are the ones
    # a desktop store is about. apt-cache cannot tell, so match on names
    # and descriptions and let the store show the best few.
    apt-cache search --names-only "$*" 2>/dev/null | head -40 | sed 's/ - /|/'
    ;;
  "")
    echo "usage: apt-install.sh package..." >&2
    exit 2
    ;;
  *)
    refresh_lists || { echo "APT_FAILED could not download the package lists"; exit 1; }
    # LINX_APT_STATUS: a file for apt's machine-readable progress lines.
    STATUS_OPT=()
    if [ -n "${LINX_APT_STATUS:-}" ]; then
      STATUS_OPT=(-o APT::Status-Fd=3)
      exec 3>>"$LINX_APT_STATUS"
    fi
    if apt-get install -y -q --no-install-recommends "${STATUS_OPT[@]}" "$@"; then
      echo "APT_DONE"
    else
      echo "APT_FAILED"
      exit 1
    fi
    ;;
esac
