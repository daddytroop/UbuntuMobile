#!/bin/bash
# Apply the desktop's look. Runs inside the container, in or alongside the
# desktop session; the running applications follow at once (XSETTINGS).
#
# Ubuntu's own Yaru theme, icons and font once the first-run pack has
# installed them (linx-setup.sh); GTK's Adwaita and Noto Sans until
# then. The window borders are the app's own theme, drawn by
# make-xfwm4-theme.py in the Yaru colours, in a 1x and a 2x size.
set -u
export DISPLAY="${DISPLAY:-:${LINX_DISPLAY:-99}}"
# ("disabled:" is what /etc/profile.d/linx-session.sh sets while there is no desktop.)
if [ "${DBUS_SESSION_BUS_ADDRESS:-disabled:}" = disabled: ] && [ -s /tmp/linx-session-bus ]; then
  DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/linx-session-bus)"
  export DBUS_SESSION_BUS_ADDRESS
fi
[ "${DBUS_SESSION_BUS_ADDRESS:-disabled:}" != disabled: ] || exit 0
command -v xfconf-query >/dev/null 2>&1 || exit 0

set_xsettings() { xfconf-query -c xsettings -p "$1" -n -t "$2" -s "$3"; }

# Light or dark, and the accent colour, as chosen when the app was set up
# (onboarding writes /etc/linx/setup.conf).
LINX_DARK=0
LINX_ACCENT=orange
[ -f /etc/linx/setup.conf ] && . /etc/linx/setup.conf
case "$LINX_ACCENT" in
  red) HEX=DA3450 ;; magenta) HEX=B34CB3 ;; purple) HEX=7764D8 ;;
  blue) HEX=0073E5 ;; prussiangreen) HEX=308280 ;; sage) HEX=657B69 ;;
  olive) HEX=4B8501 ;; yellow) HEX=C88800 ;; *) LINX_ACCENT=orange; HEX=E95420 ;;
esac
YARU=Yaru
[ "$LINX_ACCENT" = orange ] || YARU="Yaru-$LINX_ACCENT"
[ "$LINX_DARK" = 1 ] && YARU="$YARU-dark"

if [ -d "/usr/share/themes/$YARU" ]; then
  set_xsettings /Net/ThemeName string "$YARU"
elif [ "$LINX_DARK" = 1 ]; then
  set_xsettings /Net/ThemeName string Adwaita-dark
else
  set_xsettings /Net/ThemeName string Adwaita
fi
if [ -d "/usr/share/icons/$YARU" ]; then
  set_xsettings /Net/IconThemeName string "$YARU"
else
  set_xsettings /Net/IconThemeName string Adwaita
fi
# GTK 4 and libadwaita apps ask for the colour scheme instead.
xfconf-query -c xsettings -p /Gtk/ApplicationPreferDarkTheme -n -t bool -s \
  "$([ "$LINX_DARK" = 1 ] && echo true || echo false)" 2>/dev/null
mkdir -p /root/.config/gtk-4.0
printf '[Settings]\ngtk-application-prefer-dark-theme=%s\ngtk-theme-name=%s\n' \
  "$([ "$LINX_DARK" = 1 ] && echo 1 || echo 0)" "$YARU" > /root/.config/gtk-4.0/settings.ini
if fc-list 2>/dev/null | grep -q "Ubuntu-R\|Ubuntu Sans\|/ubuntu/"; then
  FONT="Ubuntu"
  MONO="Ubuntu Mono"
else
  FONT="Noto Sans"
  MONO="Noto Sans Mono"
fi
set_xsettings /Gtk/FontName string "$FONT 10"
set_xsettings /Gtk/MonospaceFontName string "$MONO 11"
xfconf-query -c xfwm4 -p /general/title_font -n -t string -s "$FONT Bold 10"

# The window borders: the app's theme when it has been drawn, sized for the
# current scale (display-scale.sh switches it).
DARK_FLAG=""
[ "$LINX_DARK" = 1 ] && DARK_FLAG="--dark"
REDRAWN="$(python3 /usr/local/lib/linx/make-xfwm4-theme.py $DARK_FLAG --accent "$HEX" 2>/dev/null)"
SCALE="$(cat /tmp/linx-ui-scale 2>/dev/null || echo 2)"
if [ -f /usr/share/themes/Linx/xfwm4/themerc ]; then
  THEME=Linx
  [ "$SCALE" -ge 2 ] && THEME=Linx-xhdpi
else
  THEME=Default
  [ "$SCALE" -ge 2 ] && THEME=Default-xhdpi
fi
# xfwm4 only rereads a theme whose name changes.
[ -n "$REDRAWN" ] && xfconf-query -c xfwm4 -p /general/theme -n -t string -s Default && sleep 0.3
xfconf-query -c xfwm4 -p /general/theme -n -t string -s "$THEME"

# The wallpaper the user picked: the app draws them (Wallpapers.kt) into
# /usr/local/share/linx/wallpapers. xfdesktop draws the desktop on the
# phone; feh draws it under Openbox, where there is no xfdesktop.
LINX_WALLPAPER="${LINX_WALLPAPER:-aubergine}"
WALL="/usr/local/share/linx/wallpapers/$LINX_WALLPAPER.jpg"
[ -f "$WALL" ] || WALL=/usr/share/xfce4/backdrops/xubuntu-wallpaper.png
echo "$WALL" >/tmp/linx-wallpaper
MONITORS="$(xrandr 2>/dev/null | awk '/ connected/ {print $1}')"
for m in ${MONITORS:-VNC-0} 0; do
  B="/backdrop/screen0/monitor$m/workspace0"
  xfconf-query -c xfce4-desktop -p "$B/image-style" -n -t int -s 5
  xfconf-query -c xfce4-desktop -p "$B/last-image" -n -t string -s "$WALL"
done
# A clean desktop: the home folder, not the whole file system.
xfconf-query -c xfce4-desktop -p /desktop-icons/file-icons/show-filesystem -n -t bool -s false
xfconf-query -c xfce4-desktop -p /desktop-icons/file-icons/show-removable -n -t bool -s false
command -v feh >/dev/null && ! pgrep -x xfdesktop >/dev/null && feh --no-fehbg --bg-fill "$WALL" 2>/dev/null

# A tidy menu: Yaru icons and plain names for the image's apps, XFCE's
# settings dialogs out of the way (linx-apps.py --tidy).
python3 /usr/local/lib/linx/linx-apps.py --tidy >/dev/null 2>&1

# The app menu (Whisker Menu once it is installed).
/usr/local/lib/linx/linx-panel.sh --restart >/dev/null 2>&1
echo "LOOK=$THEME"
