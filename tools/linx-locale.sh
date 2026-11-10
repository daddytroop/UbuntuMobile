#!/bin/bash
# Speak the phone's language. Runs inside the container, as root.
#
#   linx-locale.sh de_DE
#
# Compiles the locale if it is missing and, for languages other than
# English, installs Ubuntu's translations (language-pack-*). Then writes
# its name to /etc/linx/locale, which the app reads for LANG the next time
# Linux starts (LinuxRuntime.guestEnv). Prints LOCALE_READY.
set -u
L="${1:-}"
[ -n "$L" ] || { echo "usage: linx-locale.sh ll_CC" >&2; exit 2; }
LANG_ONLY="${L%%_*}"
NAME="$L.UTF-8"

has_locale() { locale -a 2>/dev/null | tr 'A-Z' 'a-z' | grep -qx "$(echo "$L.utf8" | tr 'A-Z' 'a-z')"; }

if ! has_locale; then
  grep -qx "$NAME UTF-8" /etc/locale.gen 2>/dev/null || echo "$NAME UTF-8" >>/etc/locale.gen
  locale-gen "$NAME" >/dev/null 2>&1 || true
  has_locale || { echo "LOCALE_FAILED $NAME"; exit 1; }
fi

# The image leaves out translations to stay small; the language packs
# bring them back for the phone's language.
if [ "$LANG_ONLY" != en ]; then
  /usr/local/lib/linx/apt-install.sh "language-pack-$LANG_ONLY" "language-pack-gnome-$LANG_ONLY" >/dev/null 2>&1 || true
fi

mkdir -p /etc/linx
echo "$NAME" >/etc/linx/locale
echo LOCALE_READY
