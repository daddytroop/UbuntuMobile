#!/bin/bash
# Start a test X display for the RFB client unit test.
#
#   tools/start_test_display.sh [display-number] [rfb-port] [colour]
#
# The colour is painted into the X root window and written to
# build/linx-display-colour.txt so the test can verify pixel accuracy.
set -uo pipefail

DISPLAY_NUM="${1:-77}"
RFB_PORT="${2:-5901}"
COLOUR="${3:-#123456}"
TEST=/opt/ubuntubuild/vtest
PROJ="${LINX_PROJECT:-/mnt/c/Users/troop/OneDrive/Documents/Default Project/UbuntuMobile}"

# The display runs inside the container rootfs so the test exercises exactly
# the code that ships in the APK.
[ -d "$TEST" ] || { echo "no test rootfs at $TEST"; exit 1; }

mount -t proc proc "$TEST/proc" 2>/dev/null
mount -t sysfs sysfs "$TEST/sys" 2>/dev/null
for d in null zero full random urandom tty; do
  [ -e "/dev/$d" ] && touch "$TEST/dev/$d" 2>/dev/null
  mount --bind "/dev/$d" "$TEST/dev/$d" 2>/dev/null
done
mkdir -p "$TEST/dev/pts"
mountpoint -q "$TEST/dev/pts" || mount -t devpts devpts "$TEST/dev/pts" 2>/dev/null
cp -f /etc/resolv.conf "$TEST/etc/resolv.conf" 2>/dev/null

R() { chroot "$TEST" /bin/bash -c "$1" 2>&1; }

# Refresh the display helper from the freshly built rootfs if we have one.
if [ -f "$TEST/usr/local/lib/linx/display-start.sh" ]; then
  :
elif [ -f "/opt/ubuntubuild/build-lite-amd64/usr/local/lib/linx/display-start.sh" ]; then
  mkdir -p "$TEST/usr/local/lib/linx"
  cp /opt/ubuntubuild/build-lite-amd64/usr/local/lib/linx/display-*.sh "$TEST/usr/local/lib/linx/"
  chmod +x "$TEST/usr/local/lib/linx/display-start.sh"
fi

echo "=== stopping any previous display ==="
R "pkill -f 'Xtigervnc :' 2>/dev/null; pkill -f 'Xvnc :' 2>/dev/null; sleep 1; rm -f /tmp/.X*-lock"

echo "=== starting the display ==="
R "export LINX_DISPLAY=$DISPLAY_NUM LINX_RFB_PORT=$RFB_PORT LINX_GEOMETRY=1024x600; nohup /usr/local/lib/linx/display-start.sh > /tmp/linx-display.log 2>&1 & sleep 7; echo started"
R "head -8 /tmp/linx-display.log"
R "ss -ltn 2>/dev/null | grep $RFB_PORT || echo 'NO LISTENER'"

echo "=== painting $COLOUR ==="
R "DISPLAY=:$DISPLAY_NUM xsetroot -solid '$COLOUR' 2>&1 | head -2; echo painted"

echo "=== recording the colour for the test ==="
mkdir -p "$PROJ/build" "$PROJ/app/build"
printf '%s' "$COLOUR" > "$PROJ/build/linx-display-colour.txt"
# Gradle runs unit tests with the module directory as the working directory.
printf '%s' "$COLOUR" > "$PROJ/app/build/linx-display-colour.txt"
cat "$PROJ/app/build/linx-display-colour.txt"
echo
echo "TEST_DISPLAY_READY"
