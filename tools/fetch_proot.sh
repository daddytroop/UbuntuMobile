#!/bin/bash
# Fetch the Termux proot runtime (bionic) for each Android ABI, patch it so it
# links correctly on Android, and stage it into the app.
#
# Payload: proot, loader, loader32, libtalloc.so, libandroid-shmem.so
#
# Why the patch is needed:
#  * proot links against "libtalloc.so.2", but the Android build system only
#    packages files matching *.so, so that name would never reach lib/<abi>/.
#  * proot's RUNPATH points at Termux's private directory, which does not
#    exist here.
#  * Termux's proot also has the path of its loader helper baked in
#    (/data/data/com.termux/files/usr/libexec/proot/loader). The app
#    overrides it with PROOT_LOADER / PROOT_LOADER_32, otherwise every
#    execve fails.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK="${LINX_WORK:-/opt/prootbuild}"
BASE="https://packages.termux.dev/apt/termux-main"
PROOT_VERSION="5.1.107.101"

command -v patchelf >/dev/null 2>&1 || {
  echo "patchelf is required (apt-get install patchelf)" >&2
  exit 1
}

mkdir -p "$WORK/debs" "$WORK/payload"
cd "$WORK/debs"

declare -A ABI_DIR=( [aarch64]=arm64-v8a [arm]=armeabi-v7a [x86_64]=x86_64 )

echo "=== resolving dependency packages ==="
declare -A DEB
for arch in aarch64 arm x86_64; do
  idx="$WORK/pkg_$arch.txt"
  if [ ! -s "$idx" ]; then
    curl -sfL "$BASE/dists/stable/main/binary-$arch/Packages.gz" | zcat > "$idx"
  fi
  paths=""
  for pkg in proot libtalloc libandroid-shmem; do
    f=$(awk -v pkg="$pkg" '
      BEGIN{RS="";FS="\n"}
      {
        p=""; fn="";
        for (i=1;i<=NF;i++) {
          if ($i ~ /^Package: /)  p=substr($i,10)
          if ($i ~ /^Filename: /) fn=substr($i,11)
        }
        if (p==pkg && fn!="") print fn
      }' "$idx" | head -1)
    if [ -n "$f" ]; then
      paths="$paths $f"
      echo "  $arch $pkg -> $f"
    else
      echo "  WARNING: $pkg not found for $arch" >&2
    fi
  done
  DEB[$arch]="$paths"
done

echo "=== downloading ==="
for arch in aarch64 arm x86_64; do
  for f in ${DEB[$arch]}; do
    out="$(basename "$f")"
    [ -s "$out" ] || curl -sfL --retry 3 -o "$out" "$BASE/$f"
  done
done

echo "=== assembling and patching the payload ==="
for arch in aarch64 arm x86_64; do
  dest="$WORK/payload/$arch"
  rm -rf "$dest"; mkdir -p "$dest"

  d="$WORK/x_$arch"; rm -rf "$d"; mkdir -p "$d"
  dpkg-deb -x "$WORK/debs/proot_${PROOT_VERSION}_${arch}.deb" "$d"
  base="$d/data/data/com.termux/files/usr"
  cp "$base/bin/proot" "$dest/proot"
  cp "$base/libexec/proot/loader" "$dest/loader"
  [ -f "$base/libexec/proot/loader32" ] && cp "$base/libexec/proot/loader32" "$dest/loader32"

  ld="$WORK/l_$arch"; rm -rf "$ld"; mkdir -p "$ld"
  for f in ${DEB[$arch]}; do
    out="$(basename "$f")"
    case "$out" in proot_*) continue ;; esac
    dpkg-deb -x "$WORK/debs/$out" "$ld"
  done
  find "$ld" -name 'libtalloc.so*' -exec cp -fL {} "$dest/libtalloc.so" \;
  find "$ld" -name 'libandroid-shmem.so*' -exec cp -fL {} "$dest/libandroid-shmem.so" \;

  # Patch: drop Termux's RUNPATH and rename the talloc dependency so the
  # library can live in the APK's native library directory.
  patchelf --remove-rpath "$dest/proot" 2>/dev/null || true
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$dest/proot"
  patchelf --set-soname libtalloc.so "$dest/libtalloc.so"

  chmod 755 "$dest"/* 2>/dev/null || true

  echo "  $arch:"
  readelf -d "$dest/proot" | grep -E "NEEDED|RUNPATH" | sed 's/^/    /'
  for needed in proot loader libtalloc.so libandroid-shmem.so; do
    if [ ! -f "$dest/$needed" ]; then
      echo "  ERROR: $needed missing for $arch" >&2
      exit 1
    fi
  done
  # loader32 only exists in the 64-bit builds; the 32-bit proot uses loader.
  if [ "$arch" != "arm" ] && [ ! -f "$dest/loader32" ]; then
    echo "  ERROR: loader32 missing for $arch" >&2
    exit 1
  fi
done

echo "=== staging into the app ==="
for arch in aarch64 arm x86_64; do
  flavor="${ABI_DIR[$arch]}"
  d="$PROJECT/app/src/main/assets/proot/$arch"
  mkdir -p "$d"
  rm -f "$d"/*
  cp -L "$WORK/payload/$arch"/* "$d/"
  chmod 755 "$d"/* 2>/dev/null || true
  echo "  assets/proot/$arch: $(ls "$d" | tr '\n' ' ')"

  # The shared libraries also go into the APK's native library directory,
  # where the Android linker always finds them.
  j="$PROJECT/app/src/main/jniLibs/$flavor"
  mkdir -p "$j"
  rm -f "$j"/libtalloc.so* "$j"/libandroid-shmem.so*
  cp -L "$WORK/payload/$arch/libtalloc.so" "$j/libtalloc.so"
  cp -L "$WORK/payload/$arch/libandroid-shmem.so" "$j/libandroid-shmem.so"
  echo "  jniLibs/$flavor: $(ls "$j" | tr '\n' ' ')"
done

echo "PROOT_PAYLOAD_OK"
