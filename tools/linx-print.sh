#!/bin/bash
# Printing from Linux apps, through the phone. Runs inside the container.
#
#   linx-print.sh            set up CUPS and its "Phone" printer, and start it
#   linx-print.sh --done F   (CUPS-PDF calls this) hand the PDF F to the phone
#
# Linux apps print to CUPS. Its "Phone" printer is CUPS-PDF, which writes
# each job as a PDF in /tmp/linx-print; the app is then asked to print it
# (linx-phone print), and Android's print dialog offers the phone's own
# printers - Wi-Fi printers, Mopria, Google's - or saving it as a PDF.
#
# Android keeps apps off ports under 1024, so CUPS listens only on its
# local socket (which is where Linux apps look first anyway).
set -u
OUT=/tmp/linx-print

if [ "${1:-}" = --done ]; then
  file="${2:-}"
  [ -f "$file" ] || exit 0
  # Readable by the app (CUPS-PDF writes the file as root, 0600).
  chmod 644 "$file" 2>/dev/null
  exec /usr/local/bin/linx-phone print "$file"
fi

command -v cupsd >/dev/null 2>&1 || exit 0
[ -f /usr/lib/cups/backend/cups-pdf ] || exit 0

mkdir -p "$OUT" /run/cups /var/spool/cups/tmp
chmod 1777 "$OUT"

# CUPS: the local socket only, no web interface, no printers sought on the
# network (the phone does that), and a temporary folder of its own.
CONF=/etc/cups/cupsd.conf
if ! grep -q '^# linx' "$CONF" 2>/dev/null; then
  cat >"$CONF" <<'EOF'
# linx: set by linx-print.sh
LogLevel warn
Listen /run/cups/cups.sock
Browsing No
WebInterface No
DefaultAuthType None
<Location />
  Order allow,deny
  Allow all
</Location>
<Policy default>
  <Limit All>
    Order deny,allow
  </Limit>
</Policy>
EOF
fi
FILES=/etc/cups/cups-files.conf
grep -q '^TempDir' "$FILES" 2>/dev/null || echo "TempDir /var/spool/cups/tmp" >>"$FILES"

# CUPS-PDF: into /tmp/linx-print, one name per job, then to the phone.
PDF=/etc/cups/cups-pdf.conf
if [ -f "$PDF" ]; then
  sed -i \
    -e "s|^#\?Out .*|Out $OUT|" \
    -e "s|^#\?AnonDirName .*|AnonDirName $OUT|" \
    -e "s|^#\?PostProcessing .*|PostProcessing /usr/local/lib/linx/linx-print.sh --done|" \
    -e "s|^#\?UserUMask .*|UserUMask 0022|" \
    "$PDF"
  grep -q '^PostProcessing' "$PDF" || echo "PostProcessing /usr/local/lib/linx/linx-print.sh --done" >>"$PDF"
  grep -q '^Out ' "$PDF" || echo "Out $OUT" >>"$PDF"
fi

if ! pgrep -x cupsd >/dev/null; then
  env -u TMPDIR cupsd >/tmp/linx-cupsd.log 2>&1
  for _ in $(seq 1 10); do
    [ -S /run/cups/cups.sock ] && break
    sleep 1
  done
fi

if ! lpstat -p Phone >/dev/null 2>&1; then
  ppd="$(ls /usr/share/ppd/cups-pdf/*.ppd 2>/dev/null | head -n 1)"
  lpadmin -p Phone -D "Your phone's printers" -L "Mobuntu" -E -v cups-pdf:/ ${ppd:+-P "$ppd"} &&
    lpadmin -d Phone
fi
lpstat -p Phone >/dev/null 2>&1 && echo "PRINT_READY"
