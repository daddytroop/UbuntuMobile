#!/bin/bash
# Say whether a Linux app is listening to the phone's microphone. Runs
# inside the container for as long as the sound server does.
#
# Writes 1 to /tmp/linx-mic-wanted while anything records from the
# "phonemic" source (linx-audio.sh), and 0 otherwise. The app (MicBridge.kt)
# reads the file and turns the phone's microphone on and off to match, so
# the microphone - and Android's microphone indicator - is only on while
# an app is actually recording.
set -u
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-root}"
WANTED=/tmp/linx-mic-wanted

update() {
  local idx n
  idx="$(pactl list short sources 2>/dev/null | awk '$2 == "phonemic" {print $1}')"
  n=0
  [ -n "$idx" ] && n="$(pactl list short source-outputs 2>/dev/null | awk -v s="$idx" '$2 == s' | wc -l)"
  if [ "$n" -gt 0 ]; then echo 1 >"$WANTED.new"; else echo 0 >"$WANTED.new"; fi
  mv -f "$WANTED.new" "$WANTED"
}

while true; do
  update
  # Every recording that starts or stops is a source-output event.
  pactl subscribe 2>/dev/null | while read -r line; do
    case "$line" in
      *source-output*) update ;;
    esac
  done
  # The sound server went away; look again shortly.
  echo 0 >"$WANTED"
  sleep 3
done
