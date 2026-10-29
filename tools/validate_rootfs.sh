#!/bin/bash
# Validate a built rootfs inside a chroot (WSL, running as root).
#
#   tools/validate_rootfs.sh arm64
#
# This is the end-to-end check that the container actually works: dpkg parses,
# apt installs, TLS works, Flathub registers and flatpak can install an app.
set -uo pipefail

ARCH="${1:-amd64}"
VARIANT="${2:-lite}"
OUT="${LINX_OUT:-/opt/ubuntubuild/out}"
WORK="${LINX_WORK:-/opt/ubuntubuild/build-${VARIANT}-${ARCH}}"
TEST=/opt/ubuntubuild/vtest

[ -d "$WORK" ] || { echo "no build dir: $WORK"; exit 2; }

echo "=== staging a copy ==="
rm -rf "$TEST"; mkdir -p "$TEST"
tar -czf /tmp/rt.tar.gz -C "$WORK" . && tar -xzf /tmp/rt.tar.gz -C "$TEST" && rm -f /tmp/rt.tar.gz

mount -t proc proc "$TEST/proc" 2>/dev/null
mount -t sysfs sysfs "$TEST/sys" 2>/dev/null
mount --bind /dev "$TEST/dev" 2>/dev/null
cp -f /etc/resolv.conf "$TEST/etc/resolv.conf" 2>/dev/null

R() { chroot "$TEST" /bin/bash -c "$1" 2>&1; }

echo "=== 1. dpkg parses cleanly ==="
R "dpkg-query -W -f='\${Package}\n' >/dev/null && echo DPKG_OK || echo DPKG_FAIL"

echo "=== 2. os-release ==="
R "grep PRETTY /etc/os-release"

echo "=== 3. bash + coreutils ==="
R "bash --version | head -1; echo hello | tr a-z A-Z"

echo "=== 4. apt update ==="
R "apt-get update -qq 2>&1 | tail -2; echo APT_RC=\$?"

echo "=== 5. apt install (real) ==="
R "apt-get install -y -qq --no-install-recommends sl 2>&1 | tail -2; echo INSTALL_RC=\$?"

echo "=== 6. TLS ==="
R "curl -sS --max-time 25 -o /dev/null -w 'tls_http=%{http_code}\n' https://flathub.org/api/v2/appstream"

echo "=== 7. firstboot (flathub setup) ==="
R "/usr/local/lib/linx/firstboot.sh 2>&1 | tail -4"

echo "=== 8. flatpak remotes ==="
R "flatpak remotes -d 2>&1 | cut -f1,9"

echo "=== 9. flatpak catalogue ==="
R "timeout 300 flatpak remote-ls flathub --app --columns=application 2>&1 | head -3"

echo "=== 10. flatpak install a small app ==="
R "timeout 900 flatpak install -y flathub org.gnome.Maps 2>&1 | tail -4"

echo "=== 11. flatpak list ==="
R "/usr/local/lib/linx/flatpak-list.sh 2>&1 | head -5"

echo "=== 12. dev toolchain ==="
R "printf 'int main(){return 0;}' > /tmp/t.c && gcc /tmp/t.c -o /tmp/t && /tmp/t && echo GCC_OK; python3 -c 'print(\"PY_OK\")'"

umount "$TEST/dev" 2>/dev/null; umount "$TEST/proc" 2>/dev/null; umount "$TEST/sys" 2>/dev/null
echo "VALIDATE_DONE"
