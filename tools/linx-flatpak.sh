#!/bin/bash
# Make Flatpak apps start under proot. Runs inside the container, as root.
#
# Flatpak runs apps in a bwrap sandbox that needs kernel namespaces proot
# cannot provide. linx-bwrap builds the same file system view with a nested
# proot instead, so it takes /usr/bin/bwrap's place (the real one is kept as
# bwrap.real, and dpkg keeps it there through upgrades). Prints FLATPAK_READY
# when Flatpak apps can run.
set -u
export DEBIAN_FRONTEND=noninteractive

if ! command -v proot >/dev/null 2>&1; then
  /usr/local/lib/linx/apt-install.sh proot >/dev/null || { echo "FLATPAK_FAILED proot"; exit 1; }
fi

if [ ! -e /usr/bin/bwrap.real ]; then
  if ! dpkg-divert --quiet --local --rename --add --divert /usr/bin/bwrap.real /usr/bin/bwrap 2>/dev/null; then
    # Run from the app, dpkg-divert fails with "Function not implemented":
    # it checks the file with faccessat2, which Android's seccomp filter
    # for apps rejects (proot passes the call through). So the diversion
    # is written by hand - three lines in dpkg's own format, ":" for a
    # local one - and the file moved the way --rename would.
    grep -qx /usr/bin/bwrap /var/lib/dpkg/diversions 2>/dev/null ||
      printf '/usr/bin/bwrap\n/usr/bin/bwrap.real\n:\n' >>/var/lib/dpkg/diversions
    mv /usr/bin/bwrap /usr/bin/bwrap.real || { echo "FLATPAK_FAILED divert"; exit 1; }
  fi
fi
if ! cmp -s /usr/local/lib/linx/linx-bwrap /usr/bin/bwrap; then
  install -m 0755 /usr/local/lib/linx/linx-bwrap /usr/bin/bwrap
fi

# Flathub's remote ships with the image; make sure it is on.
if ! flatpak remotes 2>/dev/null | grep -q '^flathub'; then
  flatpak remote-add --if-not-exists --from flathub /usr/local/share/linx/flathub.flatpakrepo >/dev/null 2>&1
fi
flatpak remote-modify --enable flathub >/dev/null 2>&1
# Translations only for the phone's language: every runtime otherwise
# brings hundreds of megabytes of them.
LANGS="${LINX_LANGUAGE:-en}"
[ "$LANGS" = en ] || LANGS="$LANGS;en"
flatpak config --system --set languages "$LANGS" >/dev/null 2>&1

# Installed apps show up in the desktop's menu.
mkdir -p /etc/profile.d
cat > /etc/profile.d/linx-flatpak.sh <<'EOF'
case ":${XDG_DATA_DIRS:-}:" in
  *:/var/lib/flatpak/exports/share:*) ;;
  *) export XDG_DATA_DIRS="/var/lib/flatpak/exports/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}" ;;
esac
EOF
echo "FLATPAK_READY"
