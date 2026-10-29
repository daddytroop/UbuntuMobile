#!/bin/bash
# Build the release APKs and publish them as a new GitHub release.
#
#   tools/release.sh v0.1.2 [notes-file]
#
# Every change that should reach a phone gets its own tag, so the builds are
# easy to tell apart and there is always a "latest" download.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO="${LINX_REPO:-daddytroop/UbuntuMobile}"

VERSION="${1:-}"
NOTES_FILE="${2:-}"
if [ -z "$VERSION" ]; then
  echo "usage: tools/release.sh <version> [notes-file]" >&2
  exit 2
fi
if ! [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "the version must look like v0.1.1" >&2
  exit 2
fi

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

command -v gh >/dev/null 2>&1 || { echo "the gh CLI is required" >&2; exit 1; }

cd "$PROJECT"

# ---------------------------------------------------------------- version
say "Setting the version to $VERSION"
python3 - "$VERSION" <<'PY'
import re, sys
version = sys.argv[1]
parts = version[1:].split(".")
code = int(parts[0]) * 100 + int(parts[1]) * 10 + int(parts[2])
path = "app/build.gradle.kts"
text = open(path, encoding="utf-8").read()
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = %d' % code, text)
text = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "%s"' % version[1:], text)
open(path, "w", encoding="utf-8").write(text)
print("versionCode = %d, versionName = %s" % (code, version[1:]))
PY

# ---------------------------------------------------------------- build
say "Building the release APKs"
if [ -x ./gradlew ]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
else
  echo "no Gradle found" >&2
  exit 1
fi
"$GRADLE" :app:assembleRelease

DIST="$PROJECT/dist"
rm -rf "$DIST"; mkdir -p "$DIST"
found=0
for apk in "$HOME"/AppData/Local/UbuntuMobile/build/app/outputs/apk/*/release/*.apk \
            "$PROJECT"/app/build/outputs/apk/*/release/*.apk; do
  [ -f "$apk" ] || continue
  cp "$apk" "$DIST/"
  found=1
done
[ "$found" = "1" ] || { echo "no APKs were produced" >&2; exit 1; }

# ---------------------------------------------------------------- notes
if [ -z "$NOTES_FILE" ]; then
  NOTES_FILE="$(mktemp)"
  {
    echo "## UbuntuMobile $VERSION"
    echo
    echo "Download the APK that matches your device:"
    echo
    echo "| File | ABI | Devices |"
    echo "|---|---|---|"
    echo "| \`app-arm64-release.apk\` | arm64-v8a | Almost all phones from the last decade |"
    echo "| \`app-armhf-release.apk\` | armeabi-v7a | Older / 32-bit ARM phones |"
    echo "| \`app-amd64-release.apk\` | x86_64 | Chromebooks, emulators, x86 tablets |"
    echo
    echo "Always the newest build (this URL always serves the latest release):"
    echo
    echo '```'
    echo "https://github.com/$REPO/releases/latest/download/app-arm64-release.apk"
    echo '```'
    echo
    echo "Uninstall the previous version before installing, so the on-device"
    echo "Ubuntu is re-provisioned cleanly."
  } > "$NOTES_FILE"
fi

# ---------------------------------------------------------------- publish
say "Publishing the release"
cd "$PROJECT"
git add -A
if git diff --cached --quiet; then
  echo "nothing to commit"
else
  git commit -q -m "Release $VERSION"
fi

ASSETS=()
for apk in "$DIST"/*.apk; do
  ASSETS+=("$apk")
done

gh release create "$VERSION" "${ASSETS[@]}" \
  --repo "$REPO" \
  --title "$VERSION" \
  --notes-file "$NOTES_FILE" \
  --latest

git tag -a "$VERSION" -m "UbuntuMobile $VERSION"
git push origin "$VERSION"
git push origin main

say "Done"
echo "Release: https://github.com/$REPO/releases/tag/$VERSION"
echo
echo "Latest download URLs:"
for apk in "${ASSETS[@]}"; do
  echo "  https://github.com/$REPO/releases/latest/download/$(basename "$apk")"
done
