#!/bin/bash
# ============================================================================
# Bootstrap UbuntuMobile: fetch the proot runtime, build the Ubuntu 26.04
# rootfs, stage it into the app and build the APKs.
#
#   tools/bootstrap.sh            # everything
#   tools/bootstrap.sh --skip-rootfs   # reuse an existing rootfs
#
# Requirements:
#   * WSL2 with an Ubuntu/Debian distro (root access needed for the chroot
#     steps and for building the rootfs)
#   * JDK 21, Android SDK (platform 36, build-tools 36, NDK 28, CMake 3.22)
#   * Gradle 8.14+
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
SKIP_ROOTFS=0
[ "${1:-}" = "--skip-rootfs" ] && SKIP_ROOTFS=1

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- rootfs
if [ "$SKIP_ROOTFS" = "0" ]; then
  say "Building the Ubuntu 26.04 rootfs (this takes ~5 minutes per architecture)"
  for arch in arm64 amd64 armhf; do
    echo "--- $arch ---"
    bash "$HERE/build_rootfs.sh" "$arch" lite
  done
  say "Staging the rootfs into the app"
  bash "$HERE/stage_rootfs.sh" lite
else
  say "Skipping the rootfs build (--skip-rootfs)"
fi

# ---------------------------------------------------------------- proot
say "Fetching the proot runtime"
bash "$HERE/fetch_proot.sh"

# ---------------------------------------------------------------- apk
say "Building the APKs"
if command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
elif [ -n "${GRADLE_BIN:-}" ]; then
  GRADLE="$GRADLE_BIN"
else
  echo "gradle not found on PATH; set GRADLE_BIN or install Gradle 8.14+"
  exit 1
fi

cd "$PROJECT"
if [ -f keystore/release.jks ]; then
  "$GRADLE" :app:assembleRelease
else
  echo "No release keystore yet - building the debug APK instead."
  echo "Create one with:"
  echo "  keytool -genkeypair -v -keystore keystore/release.jks -storepass ubuntu \\"
  echo "          -keypass ubuntu -alias ubuntu -keyalg RSA -keysize 4096 -validity 10000"
  "$GRADLE" :app:assembleDebug
fi

say "Done"
echo "APKs:"
find "$PROJECT" "$HOME/AppData/Local/UbuntuMobile/build" -name "*.apk" 2>/dev/null | head -10 || true
