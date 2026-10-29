#!/bin/bash
# ============================================================================
# Build upstream proot for the *host*, so the container can be exercised on a
# build machine.
#
# The proot that ships in the APK is an Android build and cannot run on a
# build server. Ubuntu's proot package is close, but it does not ship the
# "loader" helper that proot needs in order to start guest binaries, so
# every execve fails with "Permission denied".
#
# Usage: tools/build_proot_host.sh [version]
# ============================================================================
set -euo pipefail

VER="${1:-${PROOT_VERSION:-5.4.0}}"
PREFIX="${PROOT_HOST_PREFIX:-/opt/proot-host}"
SRC="$PREFIX/proot-$VER"

mkdir -p "$PREFIX"

if [ ! -d "$SRC" ]; then
  echo "=== fetching proot $VER ==="
  curl -sfL --retry 3 -o "$PREFIX/proot.tar.gz" \
    "https://github.com/proot-me/proot/archive/refs/tags/v$VER.tar.gz"
  # The archive already unpacks into "proot-<version>".
  rm -rf "$PREFIX/proot-$VER"
  tar -xzf "$PREFIX/proot.tar.gz" -C "$PREFIX"
  [ -d "$SRC" ] || { echo "unexpected archive layout" >&2; exit 1; }
fi

# proot links against talloc.
if ! pkg-config --exists talloc 2>/dev/null; then
  echo "=== installing the build dependencies ==="
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends \
    build-essential pkg-config libtalloc-dev python3 >/dev/null
fi

echo "=== building proot $VER ==="
cd "$SRC/src"
make -j"$(nproc)" >/dev/null

# The loader has to sit next to the binary. The build puts it in
# src/loader/loader, because src/loader is the directory with its sources.
mkdir -p "$PREFIX/bin"
cp -f "$SRC/src/proot" "$PREFIX/bin/proot"
cp -f "$SRC/src/loader/loader" "$PREFIX/bin/loader"
cp -f "$SRC/src/loader/loader-m32" "$PREFIX/bin/loader32" 2>/dev/null || true
chmod 0755 "$PREFIX/bin/proot" "$PREFIX/bin/loader" "$PREFIX/bin/loader32" 2>/dev/null || true

echo "=== done ==="
"$PREFIX/bin/proot" --version 2>&1 | head -2
ls -la "$PREFIX/bin/"
