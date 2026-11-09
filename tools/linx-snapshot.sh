#!/bin/bash
# The whole Linux system as one file, and back. Runs inside the container,
# as root; the app streams the file to or from wherever the user picked
# (Downloads, Drive...).
#
#   linx-snapshot.sh --backup     write a .tar.zst of / to standard output
#   linx-snapshot.sh --restore    read one from standard input and unpack it
#                                 over /, then print SNAPSHOT_RESTORED
#
# Left out: what is not the system's own - the phone's folders and Android's
# file systems bound in by proot, /tmp, and caches that are downloaded again
# (apt's packages, thumbnails).
set -o pipefail
cd / || exit 1

EXCLUDES=(
  --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./data
  --exclude=./sdcard --exclude=./storage --exclude=./tmp --exclude=./run
  --exclude=./var/cache/apt/archives/*.deb --exclude=./root/.cache
  --exclude=./var/tmp
)

case "${1:-}" in
  --backup)
    tar --numeric-owner --xattrs -cpf - "${EXCLUDES[@]}" . 2>/tmp/linx-snapshot.log \
      | zstd -q -T0 -3
    ;;
  --restore)
    # Over the running system: tar replaces each file (unlink, then write),
    # so programs that are running keep their old copy until they restart.
    zstd -q -d -c | tar --numeric-owner -xpf - -C / 2>/tmp/linx-snapshot.log \
      && echo "SNAPSHOT_RESTORED"
    ;;
  *)
    echo "usage: linx-snapshot.sh --backup|--restore" >&2
    exit 2
    ;;
esac
