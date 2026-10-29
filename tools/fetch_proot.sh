#!/bin/bash
# Fetch the Termux proot runtime (bionic) for each Android ABI and stage it
# into the app's shared assets.
#
# The payload is: proot, loader, loader32, libtalloc.so.2, libandroid-shmem.so
#
# IMPORTANT: Termux's proot has the path of its loader helper baked in
# (/data/data/com.termux/files/usr/libexec/proot/loader). The app overrides
# it with PROOT_LOADER / PROOT_LOADER_32, otherwise every execve fails.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJ="${LINX_PROJECT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
WORK="${LINX_WORK:-/opt/prootbuild}"
BASE="https://packages.termux.dev/apt/termux-main"

mkdir -p "$WORK/debs" "$WORK/payload"
cd "$WORK/debs"

declare -A DEB
DEB[aarch64]="proot_5.1.107.101_aarch64.deb"
DEB[arm]="proot_5.1.107.101_arm.deb"
DEB[x86_64]="proot_5.1.107.101_x86_64.deb"

echo "=== resolving dependency packages ==="
for arch in aarch64 arm x86_64; do
  idx="$WORK/pkg_$arch.txt"
  [ -f "$idx" ] || { curl -s "$BASE/dists/stable/main/binary-$arch/Packages.gz" | zcat > "$idx"; }
  for pkg in libtalloc libandroid-shmem; do
    f=$(awk -v pkg="$pkg" 'BEGIN{RS="\n\n";FS="\n"} {p="";fn=""; for(i=1;i<=NF;i++){if($i ~ /^Package: /)p=substr($i,10); if($i ~ /^Filename: /)fn=substr($i,11)} if(p==pkg && fn!="") print fn}' "$idx" | head -1)
    if [ -n "$f" ]; then
      DEB[$arch]="${DEB[$arch]} $(basename "$f")"
      echo "  $arch $pkg -> $f"
    fi
  done
done

echo "=== downloading ==="
for arch in aarch64 arm x86_64; do
  for f in ${DEB[$arch]}; do
    [ -f "$f" ] || curl -sfL -o "$f" "$BASE/pool/main/$(echo "$f" | sed 's|^[^/]*/||')" 2>/dev/null \
      || curl -sfL -o "$f" "$BASE/pool/main/$(dirname $(dirname "$f"))/$(basename "$f")"
  done
done

echo "=== assembling payload ==="
for arch in aarch64 arm x86_64; do
  dest="$WORK/payload/$arch"
  rm -rf "$dest"; mkdir -p "$dest"
  d="$WORK/x_$arch"
  rm -rf "$d"; mkdir -p "$d"
  dpkg-deb -x "$WORK/debs/proot_5.1.107.101_${arch}.deb" "$d"
  base="$d/data/data/com.termux/files/usr"
  cp "$base/bin/proot" "$dest/proot"
  cp "$base/libexec/proot/loader" "$dest/loader"
  [ -f "$base/libexec/proot/loader32" ] && cp "$base/libexec/proot/loader32" "$dest/loader32"
  for f in ${DEB[$arch]}; do
    case "$f" in
      proot_*) continue ;;
    esac
    ld="$WORK/l_$arch"; rm -rf "$ld"; mkdir -p "$ld"
    dpkg-deb -x "$WORK/debs/$f" "$ld"
    find "$ld" -name 'libtalloc.so*' -exec cp -P {} "$dest/" \;
    find "$ld" -name 'libandroid-shmem.so*' -exec cp -P {} "$dest/" \;
  done
  # proot needs the soname libtalloc.so.2
  [ -f "$dest/libtalloc.so.2" ] || cp -P "$dest/libtalloc.so.2.5.0" "$dest/libtalloc.so.2" 2>/dev/null || true
  chmod 755 "$dest"/* 2>/dev/null || true
  echo "  $arch: $(ls "$dest" | tr '\n' ' ')"
done

echo "=== staging into the app ==="
for pair in "aarch64:arm64" "arm:armhf" "x86_64:amd64"; do
  src="${pair%%:*}"; flavor="${pair##*:}"
  d="$PROJ/app/src/main/assets/proot/$src"
  mkdir -p "$d"
  rm -f "$d"/*
  cp -L "$WORK/payload/$src"/* "$d/"
  chmod 755 "$d"/* 2>/dev/null || true
  echo "  $flavor <- $(ls "$d" | tr '\n' ' ')"
done

du -sh "$PROJ/app/src/main/assets/proot"
echo "PROOT_PAYLOAD_OK"
