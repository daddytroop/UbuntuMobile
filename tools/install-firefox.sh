#!/bin/bash
# Install Firefox from Mozilla's own apt repository. Runs inside the
# container, as root.
#
# Ubuntu's "firefox" package only installs the snap, and snaps cannot run
# here (no snapd, no systemd, no mounts), so Firefox comes from
# packages.mozilla.org instead, pinned above Ubuntu's package. Mozilla
# builds it for amd64 and arm64; 32-bit ARM gets GNOME Web (epiphany).
#
# Prints FIREFOX_READY (or BROWSER_READY for GNOME Web) when done.
set -eu
export DEBIAN_FRONTEND=noninteractive

if [ -x /usr/lib/firefox/firefox ]; then
  echo "FIREFOX_READY"
  exit 0
fi

ARCH="$(dpkg --print-architecture)"
case "$ARCH" in
  amd64|arm64) ;;
  *)
    /usr/local/lib/linx/apt-install.sh epiphany-browser
    echo "BROWSER_READY"
    exit 0
    ;;
esac

install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg \
  -o /etc/apt/keyrings/packages.mozilla.org.asc
echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" \
  >/etc/apt/sources.list.d/mozilla.list
cat >/etc/apt/preferences.d/mozilla <<'EOF'
Package: *
Pin: origin packages.mozilla.org
Pin-Priority: 1000
EOF

# A new source always needs its index, whatever apt-install.sh thinks.
rm -f /var/lib/apt/lists/.linx-updated
/usr/local/lib/linx/apt-install.sh firefox
[ -x /usr/lib/firefox/firefox ] || { echo "FIREFOX_FAILED"; exit 1; }
echo "FIREFOX_READY"
