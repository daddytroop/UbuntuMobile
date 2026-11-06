#!/bin/bash
# Put the phone's own folders in the Linux home folder. Runs inside the
# container (the phone's storage is bound at /sdcard).
#
#   ~/Downloads -> Download   ~/Documents -> Documents
#   ~/Pictures  -> Pictures   ~/Music     -> Music
#   ~/Videos    -> Movies
#
# Only once the app may read the phone's storage, and never over a folder
# that has something in it.
set -u
HOME="${HOME:-/root}"
for pair in Downloads:Download Documents:Documents Pictures:Pictures Music:Music Videos:Movies; do
  name="${pair%%:*}"
  src="/sdcard/${pair#*:}"
  dst="$HOME/$name"
  [ -d "$src" ] && [ -r "$src" ] || continue
  [ -L "$dst" ] && continue
  if [ -d "$dst" ]; then
    rmdir "$dst" 2>/dev/null || continue
  fi
  [ -e "$dst" ] || ln -s "$src" "$dst"
done
echo "FOLDERS_DONE"
