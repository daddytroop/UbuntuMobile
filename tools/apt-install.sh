#!/bin/bash
# Install Ubuntu packages for the app's store. Runs inside the container.
#
#   apt-install.sh firefox             install (and say APT_DONE)
#   apt-install.sh --status pkg...     print "pkg installed|missing" lines
#   apt-install.sh --search words      print "pkg|summary" for matches
#   apt-install.sh --remove pkg...     uninstall (and say APT_DONE)
#
# The image ships without apt's package lists (they are large and go stale),
# so the first install fetches them; after that they are refreshed once a
# day at most.
set -u
export DEBIAN_FRONTEND=noninteractive

# One at a time: setup, the store and the desktop's background extras all
# come through here, and two apt-gets at once fail on apt's lock (which
# looked like "could not download the package lists"). The others wait;
# a search does not (it makes do with the lists there are). The lock is
# this script's alone: everything it starts gets it closed (9>&-), or a
# service started by a package would hold it for good.
BUSY=0
case "${1:-}" in
  --status) ;;
  --search)
    exec 9>/tmp/linx-apt.lock
    flock -n 9 || BUSY=1
    ;;
  *)
    exec 9>/tmp/linx-apt.lock
    if ! flock -w 3600 9; then
      echo "APT_FAILED another install is still running"
      exit 1
    fi
    ;;
esac
STAMP=/var/lib/apt/lists/.linx-updated

# apt-get update, and when it fails, what can be done here about it:
#   - names do not resolve: the phone's DNS servers do not answer the
#     container, so public ones are put first;
#   - the download is refused or garbled: some Wi-Fi and mobile networks
#     meddle with plain HTTP, so Ubuntu's archive is fetched over HTTPS;
#   - and a second go for a dropped connection.
# On failure the reason apt gave is the last line printed.
update_lists() {
  local log=/tmp/linx-apt-update.log
  apt-get update -q 2>&1 9>&- | tee "$log"
  # apt says "W: Failed to fetch" for some failures and still exits 0.
  [ "${PIPESTATUS[0]}" = 0 ] && ! grep -qE '^(E|W): Failed to fetch https?://[a-z.]*ubuntu.com' "$log"
}
can_resolve() { getent hosts "$1" >/dev/null 2>&1; }
archive_host() {
  grep -rhE '^(deb|URIs:)' /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null |
    grep -oE 'https?://[a-z.]*ubuntu\.com' | head -n 1 | sed 's,.*://,,'
}
refresh_lists() {
  [ -n "$(find "$STAMP" -mmin -1440 2>/dev/null)" ] && return 0
  update_lists && { touch "$STAMP"; return 0; }
  local host
  host="$(archive_host)"
  if [ -n "$host" ] && ! can_resolve "$host"; then
    echo "linx: $host does not resolve; trying public DNS servers"
    {
      echo "# Written by apt-install.sh: the phone's DNS servers did not answer."
      echo "nameserver 1.1.1.1"
      echo "nameserver 8.8.8.8"
      grep '^nameserver' /etc/resolv.conf 2>/dev/null | head -n 1
      echo "options timeout:2 attempts:2"
    } >/tmp/linx-resolv.conf && cat /tmp/linx-resolv.conf >/etc/resolv.conf
    if ! can_resolve "$host"; then
      echo "E: no internet: $host cannot be found (is the phone online?)"
      return 1
    fi
    update_lists && { touch "$STAMP"; return 0; }
  fi
  if grep -rqE 'http://[a-z.]*ubuntu\.com' /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null; then
    echo "linx: trying Ubuntu's archive over HTTPS"
    find /etc/apt/sources.list /etc/apt/sources.list.d -type f \( -name '*.list' -o -name '*.sources' -o -name sources.list \) \
      -exec sed -i -E 's,http://([a-z.]*ubuntu\.com),https://\1,g' {} + 2>/dev/null
    update_lists && { touch "$STAMP"; return 0; }
  fi
  update_lists && { touch "$STAMP"; return 0; }
  grep -E '^(E|W): ' /tmp/linx-apt-update.log | tail -n 1
  return 1
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
    [ "$BUSY" = 1 ] || refresh_lists >/dev/null 2>&1
    # Applications only: packages that come with a menu entry are the ones
    # a desktop store is about. apt-cache cannot tell, so match on names
    # and descriptions and let the store show the best few.
    apt-cache search --names-only "$*" 2>/dev/null | head -40 | sed 's/ - /|/'
    ;;
  --remove)
    shift
    [ $# -gt 0 ] || { echo "APT_FAILED nothing to remove"; exit 2; }
    # What only these needed goes too (--autoremove); settings in the home
    # folder stay.
    if apt-get remove -y -q --autoremove "$@" 9>&-; then
      echo "APT_DONE"
    else
      echo "APT_FAILED"
      exit 1
    fi
    ;;
  "")
    echo "usage: apt-install.sh package..." >&2
    exit 2
    ;;
  *)
    if ! refresh_lists; then
      echo "APT_FAILED could not download the package lists"
      # The reason, last, for the setup's message.
      grep -E '^E: ' /tmp/linx-apt-update.log 2>/dev/null | tail -n 1 | sed 's/^/error: /'
      exit 1
    fi
    # LINX_APT_STATUS: a file for apt's machine-readable progress lines.
    STATUS_OPT=()
    if [ -n "${LINX_APT_STATUS:-}" ]; then
      STATUS_OPT=(-o APT::Status-Fd=3)
      exec 3>>"$LINX_APT_STATUS"
    fi
    if apt-get install -y -q --no-install-recommends "${STATUS_OPT[@]}" "$@" 9>&-; then
      # New menu entries get the same clean-up as the image's: apt does
      # it after every install, the terminal's too (apt.conf.d/99linx-tidy).
      [ -f /etc/apt/apt.conf.d/99linx-tidy ] ||
        python3 /usr/local/lib/linx/linx-apps.py --tidy >/dev/null 2>&1 9>&-
      echo "APT_DONE"
    else
      echo "APT_FAILED"
      exit 1
    fi
    ;;
esac
