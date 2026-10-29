#!/bin/bash
# Stage the built rootfs images into the app's per-flavor asset folders.
#
# The APK ships one rootfs per ABI flavor, so each generated APK only carries
# the image it needs. The archive is renamed from .tar.gz to .rootfs because
# aapt2 treats a ".gz" suffix specially: it decompresses the asset and
# strips the extension from the packaged name.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJ="${LINX_PROJECT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
OUT="${LINX_OUT:-/opt/ubuntubuild/out}"
VARIANT="${1:-lite}"

stage() {
  local flavor="$1" arch="$2"
  local src="$OUT/ubuntu-26.04-${VARIANT}-${arch}.tar.gz"
  local dir="$PROJ/app/src/$flavor/assets/rootfs"
  mkdir -p "$dir"
  rm -f "$dir"/*.rootfs "$dir"/*.tar.gz
  if [ -f "$src" ]; then
    cp "$src" "$dir/ubuntu-26.04-lite-${arch}.rootfs"
    echo "  $flavor <- $(basename "$src") ($(du -h "$src" | cut -f1))"
  else
    echo "  $flavor <- MISSING $src"
  fi
}

echo "=== staging ($VARIANT) ==="
stage arm64 arm64
stage armhf armhf
stage amd64 amd64

echo "=== result ==="
find "$PROJ/app/src" -name "*.rootfs" | sed "s|$PROJ/app/src/||"
echo "STAGE_OK"
