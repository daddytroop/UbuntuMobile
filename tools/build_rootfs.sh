#!/bin/bash
# ============================================================================
# Build an Ubuntu 26.04 rootfs for UbuntuMobile.
#
#   tools/build_rootfs.sh <arm64|armhf|amd64> [lite|full]
#
# Produces /opt/ubuntubuild/out/ubuntu-26.04-<variant>-<arch>.tar.gz
#
# Requires WSL2 (Ubuntu) running as root. See README.md for the rationale
# behind each step - several of them fix subtle breakage.
# ============================================================================
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# Resolve this script's directory before any `cd`, so the helper
# files next to it can be copied in reliably.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

ARCH="${1:-arm64}"
VARIANT="${2:-lite}"
OUT="${LINX_OUT:-/opt/ubuntubuild/out}"
DL="${LINX_DL:-/opt/ubuntubuild/dl}"
REL="26.04.1"
CDURL="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release"
SUITE="resolute"
TARBALL="ubuntu-base-${REL}-base-${ARCH}.tar.gz"

mkdir -p "$OUT" "$DL"
cd "$DL"

# ---------------------------------------------------------------- base image
echo "=== [$ARCH/$VARIANT] base image ==="
[ -f "$TARBALL" ] || curl -fL --retry 3 -o "$TARBALL" "$CDURL/$TARBALL"
gzip -t "$TARBALL"
BASE_COUNT=$(tar -tzf "$TARBALL" | wc -l)
echo "  entries: $BASE_COUNT"
[ "$BASE_COUNT" -gt 2000 ] || { echo "BASE_TARBALL_TRUNCATED"; exit 1; }

WORK="/opt/ubuntubuild/build-${VARIANT}-${ARCH}"
echo "=== [$ARCH/$VARIANT] extracting base ==="
rm -rf "$WORK"; mkdir -p "$WORK"
tar -xzf "$TARBALL" -C "$WORK"
[ -e "$WORK/usr/bin/bash" ] || { echo "BASE_EXTRACT_BROKEN"; exit 1; }

# ------------------------------------------------------------- apt plumbing
case "$ARCH" in
  amd64) MIRROR="http://archive.ubuntu.com/ubuntu"; SEC="http://security.ubuntu.com/ubuntu" ;;
  arm64) MIRROR="http://ports.ubuntu.com/ubuntu-ports"; SEC="http://ports.ubuntu.com/ubuntu-ports" ;;
  # armhf is not published to the security pocket; main pocket only.
  armhf) MIRROR="http://ports.ubuntu.com/ubuntu-ports"; SEC="" ;;
esac

PARTS="$DL/parts-${VARIANT}-${ARCH}"; mkdir -p "$PARTS"
cat > "$PARTS/ubuntu.sources" <<EOF
Types: deb
URIs: $MIRROR/
Suites: $SUITE $SUITE-updates
Components: main universe restricted multiverse
Signed-By: $WORK/usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
if [ -n "$SEC" ]; then
cat > "$PARTS/ubuntu-security.sources" <<EOF
Types: deb
URIs: $SEC/
Suites: $SUITE-security
Components: main universe restricted multiverse
Signed-By: $WORK/usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
fi

LISTDIR="$DL/lists-${VARIANT}-${ARCH}"; rm -rf "$LISTDIR"; mkdir -p "$LISTDIR/partial"
CACHE="$DL/cache-${VARIANT}-${ARCH}"; rm -rf "$CACHE"; mkdir -p "$CACHE/archives/partial"
CONF="$DL/apt-${VARIANT}-${ARCH}.conf"
cat > "$CONF" <<EOF
Dir::Etc::sourcelist "/dev/null";
Dir::Etc::sourceparts "$PARTS";
Dir::State::lists "$LISTDIR";
Dir::Cache "$CACHE";
Dir::State::status "$WORK/var/lib/dpkg/status";
APT::Architecture "$ARCH";
APT::Architectures:: "$ARCH";
Acquire::Languages "none";
EOF

echo "=== [$ARCH/$VARIANT] apt update ==="
apt-get -c "$CONF" update 2>&1 | tail -2

if [ "$VARIANT" = "lite" ]; then
PKGS="ca-certificates locales tzdata sudo \
curl wget git gnupg openssh-client \
tar gzip zstd xz-utils unzip zip bzip2 \
nano less tree file jq ripgrep \
procps psmisc htop lsof pciutils usbutils \
iproute2 iputils-ping dnsutils netcat-openbsd socat \
python3 python3-pip python3-venv \
build-essential pkg-config make cmake ninja-build \
dbus dbus-x11 \
libgl1-mesa-dri libglx-mesa0 mesa-utils libgbm1 libegl1 libgles2 \
pulseaudio-utils alsa-utils libasound2t64 \
fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji \
flatpak flatpak-xdg-utils bubblewrap appstream appstream-util \
xdg-user-dirs desktop-file-utils shared-mime-info \
    tigervnc-standalone-server xvfb x11vnc x11-utils x11-xserver-utils xdotool xterm xfonts-base \
    gnu-coreutils"
else
PKGS="ca-certificates locales tzdata sudo \
curl wget git gnupg openssh-client \
tar gzip zstd xz-utils unzip zip bzip2 \
nano less tree file jq ripgrep \
procps psmisc htop lsof pciutils usbutils \
iproute2 iputils-ping dnsutils netcat-openbsd socat \
python3 python3-pip python3-venv python3-dev \
build-essential pkg-config make cmake ninja-build \
dbus dbus-x11 \
libgl1-mesa-dri libglx-mesa0 mesa-utils mesa-vulkan-drivers \
libgbm1 libegl1 libgles2 libvulkan1 \
pulseaudio-utils alsa-utils libasound2t64 ffmpeg \
fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji fonts-noto-cjk \
flatpak flatpak-xdg-utils bubblewrap appstream appstream-util \
xdg-user-dirs desktop-file-utils shared-mime-info \
    tigervnc-standalone-server xvfb x11vnc x11-utils x11-xserver-utils xdotool xterm xfonts-base \
    gnu-coreutils"
fi

echo "=== [$ARCH/$VARIANT] downloading packages ==="
apt-get -c "$CONF" -o Dir::Cache::archives="$CACHE/archives" \
  --download-only --no-install-recommends -y install $PKGS 2>&1 | tail -3
N=$(ls "$CACHE/archives"/*.deb 2>/dev/null | wc -l)
echo "  $N debs ($(du -sh "$CACHE/archives" | cut -f1))"
[ "$N" -gt 40 ] || { echo "TOO_FEW_DEPS"; exit 1; }

echo "=== [$ARCH/$VARIANT] extracting packages ==="
for d in "$CACHE/archives"/*.deb; do dpkg-deb -x "$d" "$WORK"; done

# --------------------------------------------------- usr-merge restoration
# Ubuntu 26.04 is merged-/usr. Tar-based extraction can clobber the
# /bin -> usr/bin symlink, which would leave the guest without /bin/bash.
echo "=== [$ARCH/$VARIANT] restoring usr-merge symlinks ==="
for pair in "bin:usr/bin" "sbin:usr/sbin" "lib:usr/lib" "lib64:usr/lib64"; do
  link="${pair%%:*}"; target="${pair##*:}"
  [ -L "$WORK/$link" ] && continue
  if [ -d "$WORK/$link" ]; then
    mkdir -p "$WORK/$target"
    (cd "$WORK/$link" && tar cf - .) | (cd "$WORK/$target" && tar xf -) 2>/dev/null || true
    rm -rf "$WORK/$link"
  fi
  ln -sfn "$target" "$WORK/$link"
  echo "  $link -> $target"
done

# ---------------------------------------------------- coreutils selection
# Ubuntu 26.04 ships coreutils TWICE:
#
#   * rust-coreutils   - the Rust (uutils) build. It installs one
#                        multicall binary, /usr/bin/coreutils, with hard
#                        links under /usr/lib/cargo/bin/coreutils/ and the
#                        familiar names as symlinks (/usr/bin/ls -> ...).
#   * gnu-coreutils    - the classic GNU build, installed under
#                        "gnu" prefixed names (/usr/bin/gnuls, ...).
#
# The Rust build picks the utility to run from AT_EXECFN, and proot sets
# AT_EXECFN to the path of its own loader helper. Every coreutils command
# in the container therefore dies with:
#
#     coreutils: unknown program 'loader'
#
# The GNU binaries are ordinary standalone programs that ignore argv[0], so
# they work under proot without any special handling. Point the standard
# names at them.
echo "=== [$ARCH/$VARIANT] selecting the GNU coreutils ==="
if [ -d "$WORK/usr/lib/cargo/bin/coreutils" ] || [ -f "$WORK/usr/bin/coreutils" ]; then
  repointed=0
  for f in "$WORK"/usr/bin/*; do
    [ -L "$f" ] || continue
    case "$(readlink "$f")" in
      ../lib/cargo/bin/coreutils/*)
        name="${f##*/}"
        if [ -f "$WORK/usr/bin/gnu$name" ]; then
          ln -sfn "gnu$name" "$f"
          repointed=$((repointed + 1))
        fi
        ;;
    esac
  done
  echo "  repointed $repointed commands to the GNU build"
  [ "$repointed" -gt 50 ] || { echo "GNU_COREUTILS_REPOINT_FAILED"; exit 1; }
  # The Rust multicall binary is no longer reachable; drop it so nothing
  # can accidentally run it.
  rm -f "$WORK/usr/bin/coreutils"
  rm -rf "$WORK/usr/lib/cargo/bin"
fi

# ---------------------------------------------------------------- resolver
# The base image ships an empty /etc/resolv.conf, so nothing inside the
# container can resolve a name and Flatpak cannot reach Flathub. The app
# rewrites this file with the phone's own DNS servers, but a working
# default means the container is usable even before that happens.
echo "=== [$ARCH/$VARIANT] writing a default resolver ==="
if [ ! -s "$WORK/etc/resolv.conf" ]; then
  rm -f "$WORK/etc/resolv.conf"
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2\n' \
    > "$WORK/etc/resolv.conf"
  echo "  wrote /etc/resolv.conf"
else
  echo "  /etc/resolv.conf already present"
fi

# ------------------------------------------------------- dpkg status merge
# Merge RAW control stanzas. Never re-serialise: dpkg requires the exact
# continuation-line formatting of multi-line fields such as Description.
echo "=== [$ARCH/$VARIANT] merging dpkg status (raw stanzas) ==="
python3 - "$WORK" "$CACHE/archives" <<'PY'
import os, sys, subprocess, tarfile, io

work, archives = sys.argv[1], sys.argv[2]
status_path = os.path.join(work, "var/lib/dpkg/status")
info_dir = os.path.join(work, "var/lib/dpkg/info")
os.makedirs(info_dir, exist_ok=True)

def split_stanzas(text):
    blocks, cur = [], []
    for line in text.split("\n"):
        if line.strip() == "":
            if cur:
                blocks.append("\n".join(cur)); cur = []
        else:
            cur.append(line)
    if cur:
        blocks.append("\n".join(cur))
    return blocks

def field(stanza, name):
    for line in stanza.split("\n"):
        if line.startswith(name + ":"):
            return line.split(":", 1)[1].strip()
    return None

def set_field(stanza, name, value):
    out, done = [], False
    for line in stanza.split("\n"):
        if not done and line.startswith(name + ":"):
            out.append(name + ": " + value); done = True
        else:
            out.append(line)
    if not done:
        out.append(name + ": " + value)
    return "\n".join(out)

def raw_control(deb_path):
    tar_bytes = subprocess.run(["dpkg-deb", "--ctrl-tarfile", deb_path],
                               capture_output=True).stdout
    with tarfile.open(fileobj=io.BytesIO(tar_bytes)) as tf:
        for m in tf.getmembers():
            if m.name.lstrip("./") == "control":
                return tf.extractfile(m).read().decode("utf-8", "replace")
    return ""

index, order = {}, []
for st in split_stanzas(open(status_path, encoding="utf-8", errors="replace").read()):
    pkg = field(st, "Package")
    if not pkg:
        continue
    key = (pkg, field(st, "Architecture") or "all")
    if key not in index:
        order.append(key)
    index[key] = st

added = replaced = 0
for f in sorted(os.listdir(archives)):
    if not f.endswith(".deb"):
        continue
    p = os.path.join(archives, f)
    ctrl = raw_control(p).strip("\n")
    if not ctrl or not field(ctrl, "Package"):
        continue
    pkg = field(ctrl, "Package")
    st = set_field(ctrl, "Status", "install ok installed")
    if field(st, "Priority") is None:
        st = set_field(st, "Priority", "optional")
    if field(st, "Section") is None:
        st = set_field(st, "Section", "misc")
    key = (pkg, field(st, "Architecture") or "all")
    if key in index:
        replaced += 1
    else:
        order.append(key); added += 1
    index[key] = st

    tar = subprocess.run(["tar", "-tf", p], capture_output=True, text=True)
    with open(os.path.join(info_dir, pkg + ".list"), "w") as lf:
        for line in tar.stdout.splitlines():
            lf.write(line.lstrip("./") + "\n")

with open(status_path, "w") as out:
    for key in order:
        out.write(index[key].rstrip("\n") + "\n\n")

blocks = split_stanzas(open(status_path, encoding="utf-8", errors="replace").read())
bad = [b.split("\n")[0] for b in blocks
       if not any(l.startswith("Description:") for l in b.split("\n"))]
print("  stanzas: %d (added %d, replaced %d), missing Description: %d"
      % (len(blocks), added, replaced, len(bad)))
if bad:
    print("  BROKEN:", bad[:5])
    sys.exit(1)
PY

# ----------------------------------------------------------- CA certificate
# ca-certificates builds this in its post-install script, which never runs
# when we only extract packages. Without it every TLS connection fails.
echo "=== [$ARCH/$VARIANT] generating CA bundle ==="
python3 - "$WORK" <<'PY'
import os, sys, subprocess, hashlib, ssl
work = sys.argv[1]
src_dir = os.path.join(work, "usr/share/ca-certificates/mozilla")
dst_dir = os.path.join(work, "etc/ssl/certs")
os.makedirs(dst_dir, exist_ok=True)

def subj_hash(path):
    try:
        out = subprocess.run(["openssl", "x509", "-in", path, "-noout", "-subject_hash"],
                             capture_output=True, text=True)
        if out.stdout.strip():
            return out.stdout.strip()
    except Exception:
        pass
    try:
        der = ssl.PEM_cert_to_DER_cert(open(path).read())
        return hashlib.sha1(der).hexdigest()[:8]
    except Exception:
        return None

bundle = []
for name in sorted(os.listdir(src_dir)):
    if not name.endswith(".crt"):
        continue
    src = os.path.join(src_dir, name)
    h = subj_hash(src)
    if not h:
        continue
    link = os.path.join(dst_dir, h + ".0")
    try:
        if os.path.islink(link) or os.path.exists(link):
            os.remove(link)
        os.symlink("/usr/share/ca-certificates/mozilla/" + name, link)
    except OSError:
        pass
    bundle.append(open(src).read().rstrip() + "\n")

with open(os.path.join(dst_dir, "ca-certificates.crt"), "w") as f:
    f.write("\n".join(bundle))
print("  CA bundle: %d certs" % len(bundle))
PY

# --------------------------------------------------------------- post-config
echo "=== [$ARCH/$VARIANT] post-config ==="
mkdir -p "$WORK/etc/flatpak" "$WORK/var/lib/flatpak/system" "$WORK/var/lib/flatpak/runtime"
chmod 1777 "$WORK/var/tmp"
cat > "$WORK/usr/sbin/policy-rc.d" <<'PRCD'
#!/bin/sh
exit 101
PRCD
chmod +x "$WORK/usr/sbin/policy-rc.d"

# bubblewrap fallback: many Android kernels forbid unprivileged user namespaces
mkdir -p "$WORK/usr/local/libexec" "$WORK/usr/local/bin"
cat > "$WORK/usr/local/libexec/bwrap-linx" <<'BW'
#!/bin/bash
# Many Android kernels forbid unprivileged user namespaces, which bubblewrap
# needs for its sandbox. Retry with --unshare-user-try instead of failing.
if /usr/bin/bwrap "$@"; then exit 0; fi
args=(); skip=0
for a in "$@"; do
  if [ "$skip" = 1 ]; then skip=0; continue; fi
  case "$a" in
    --unshare-user) skip=1; args+=("--unshare-user-try") ;;
    *) args+=("$a") ;;
  esac
done
exec /usr/bin/bwrap "${args[@]}"
BW
chmod +x "$WORK/usr/local/libexec/bwrap-linx"
ln -sfn /usr/local/libexec/bwrap-linx "$WORK/usr/local/bin/bwrap"

sed -i 's/^# *\(en_US.UTF-8\)/\1/' "$WORK/etc/locale.gen" 2>/dev/null || true

# Flathub remote definition, shipped offline so it can be added without
# network access. It embeds the GPG key.
mkdir -p "$WORK/usr/local/share/linx"
curl -sfL --max-time 90 "https://dl.flathub.org/repo/flathub.flatpakrepo" \
  -o "$WORK/usr/local/share/linx/flathub.flatpakrepo" || true
echo "  flathub repo: $(stat -c%s "$WORK/usr/local/share/linx/flathub.flatpakrepo" 2>/dev/null || echo 0) bytes"

mkdir -p "$WORK/usr/local/lib/linx"

cat > "$WORK/usr/local/lib/linx/firstboot.sh" <<'FB'
#!/bin/bash
# Runs INSIDE the container on first launch: locale + Flathub setup.
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

[ -e /var/lib/locale/C.UTF-8 ] || locale-gen en_US.UTF-8 || true

# Add Flathub from the bundled .flatpakrepo (embeds the GPG key, works offline).
# NOTE: --from is a BOOLEAN flag; LOCATION is the config file path.
if ! flatpak remotes 2>/dev/null | grep -q '^flathub$'; then
  flatpak remote-add --if-not-exists --from flathub \
      /usr/local/share/linx/flathub.flatpakrepo 2>/dev/null \
    || flatpak remote-add --if-not-exists flathub \
         https://dl.flathub.org/repo/flathub.flatpakrepo 2>/dev/null \
    || true
fi
flatpak remote-modify --enable flathub || true

[ -x /usr/local/libexec/bwrap-linx ] && ln -sf /usr/local/libexec/bwrap-linx /usr/local/bin/bwrap

mkdir -p /var/lib/flatpak /var/lib/flatpak/system
chmod 1777 /var/tmp

echo "FIRSTBOOT_OK"
FB
chmod +x "$WORK/usr/local/lib/linx/firstboot.sh"

# `flatpak search` needs an appstream catalogue that is not present in a
# minimal container, so enumerate the remote directly instead.
cat > "$WORK/usr/local/lib/linx/flatpak-search.sh" <<'FS'
#!/bin/bash
# Search the Flathub catalogue. Output: "app-id|version|name"
REMOTE="${1:-flathub}"
shift 2>/dev/null || true
flatpak remote-ls "$REMOTE" --app --columns=application,version,name "$@" 2>/dev/null \
  | awk -F'\t' 'NF>=1 {gsub(/\n/," "); if ($1!="") printf "%s|%s|%s\n", $1, ($2==""?"-":$2), ($3==""?$1:$3)}'
FS
chmod +x "$WORK/usr/local/lib/linx/flatpak-search.sh"

cat > "$WORK/usr/local/lib/linx/flatpak-list.sh" <<'FL'
#!/bin/bash
# List installed flatpak apps as "app-id|version|name".
flatpak list --app --columns=application,version,name 2>/dev/null \
  | awk -F'\t' 'NF>=1 {gsub(/\n/," "); if ($1!="") printf "%s|%s|%s\n", $1, ($2==""?"-":$2), ($3==""?$1:$3)}'
FL
chmod +x "$WORK/usr/local/lib/linx/flatpak-list.sh"

# The Flathub remote lives in the system scope, so installs must not use --user.
cat > "$WORK/usr/local/lib/linx/flatpak-install.sh" <<'FI'
#!/bin/bash
REMOTE="${FLATPAK_REMOTE:-flathub}"
APP="$1"
[ -n "$APP" ] || { echo "usage: flatpak-install.sh <app-id>"; exit 2; }
exec flatpak install -y "$REMOTE" "$APP"
FI
chmod +x "$WORK/usr/local/lib/linx/flatpak-install.sh"

cat > "$WORK/usr/local/lib/linx/flatpak-remove.sh" <<'FR'
#!/bin/bash
APP="$1"
[ -n "$APP" ] || { echo "usage: flatpak-remove.sh <app-id>"; exit 2; }
exec flatpak uninstall -y "$APP"
FR
chmod +x "$WORK/usr/local/lib/linx/flatpak-remove.sh"

# ---------------------------------------------------------------------------
# Virtual gamepad helper: turns the on-screen controller's state into Linux
# input events on a virtual uinput joystick.
# ---------------------------------------------------------------------------
cp "$SCRIPT_DIR/gamepad-helper.py" "$WORK/usr/local/lib/linx/gamepad-helper.py"
chmod +x "$WORK/usr/local/lib/linx/gamepad-helper.py"

# ---------------------------------------------------------------------------
# Display stack: Xvnc (TigerVNC) provides an X server AND an RFB export in a
# single process. The Android app connects to the RFB port directly, because
# proot shares the host network namespace - 127.0.0.1 is the phone's loopback.
# ---------------------------------------------------------------------------
# Ubuntu 26.04's tigervnc-standalone-server installs the binary as
# /usr/bin/Xtigervnc; the /usr/bin/Xvnc name is an alternatives symlink that
# is only created by the package's maintainer script, which never runs when we
# just extract the .deb. Recreate it so both names work.
if [ ! -e "$WORK/usr/bin/Xvnc" ] && [ -e "$WORK/usr/bin/Xtigervnc" ]; then
  ln -sfn /usr/bin/Xtigervnc "$WORK/usr/bin/Xvnc"
fi

cp "$SCRIPT_DIR/display-start.sh" "$WORK/usr/local/lib/linx/display-start.sh"
chmod +x "$WORK/usr/local/lib/linx/display-start.sh"
chmod +x "$WORK/usr/local/lib/linx/display-start.sh"

cat > "$WORK/usr/local/lib/linx/display-stop.sh" <<'DX'
#!/bin/bash
# Stop the X display started by display-start.sh.
DISPLAY_NUM="${LINX_DISPLAY:-99}"
pkill -f "Xvnc :$DISPLAY_NUM" 2>/dev/null
pkill -f "Xtigervnc :$DISPLAY_NUM" 2>/dev/null
rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null
echo "DISPLAY_STOPPED"
DX
chmod +x "$WORK/usr/local/lib/linx/display-stop.sh"

# ------------------------------------------------------------------- cleanup
echo "=== [$ARCH/$VARIANT] cleanup ==="
rm -rf "$WORK/var/lib/apt/lists"/* 2>/dev/null || true
find "$WORK/usr/share/doc" -mindepth 1 -delete 2>/dev/null || true
rm -rf "$WORK/usr/share/man" "$WORK/usr/share/info" 2>/dev/null || true
find "$WORK/usr/share/locale" -name '*.mo' -delete 2>/dev/null || true
rm -rf "$WORK/tmp"/* "$WORK/var/tmp"/* 2>/dev/null || true

# ----------------------------------------------------------- VERIFY (hard)
echo "=== [$ARCH/$VARIANT] VERIFY ==="
FAIL=0
check() { if [ -e "$WORK/$1" ]; then echo "  OK   $1"; else echo "  FAIL $1"; FAIL=1; fi; }
check bin/bash
check bin/ls
check bin/apt-get
check usr/bin/flatpak
check usr/bin/bwrap
check usr/bin/python3
check usr/bin/gcc
check usr/bin/curl
check usr/bin/git
check etc/os-release
check etc/ssl/certs/ca-certificates.crt
check usr/local/lib/linx/firstboot.sh
check usr/local/lib/linx/flatpak-search.sh
check usr/local/lib/linx/flatpak-install.sh
check usr/local/lib/linx/display-start.sh
check usr/local/lib/linx/display-stop.sh
check usr/local/lib/linx/gamepad-helper.py
check usr/local/share/linx/flathub.flatpakrepo
check usr/bin/xdpyinfo
check usr/bin/xdotool
check usr/bin/x11vnc
check usr/bin/Xvfb
if [ -e "$WORK/usr/bin/Xvnc" ] || [ -e "$WORK/usr/bin/Xtigervnc" ]; then
  echo "  OK   X server binary present"
else
  echo "  FAIL X server binary missing"; FAIL=1
fi
[ "$FAIL" = "0" ] || { echo "VERIFY_FAILED"; exit 1; }

# ----------------------------------------------------------------- packaging
echo "=== [$ARCH/$VARIANT] packaging ==="
TAROUT="$OUT/ubuntu-26.04-${VARIANT}-${ARCH}.tar.gz"
tar -czf "$TAROUT" -C "$WORK" .
sha256sum "$TAROUT" > "$TAROUT.sha256"
gzip -t "$TAROUT"

echo "=== [$ARCH/$VARIANT] VERIFY ARCHIVE ==="
LIST=$(mktemp); tar -tzf "$TAROUT" > "$LIST"
checka() { if grep -qx "$1" "$LIST"; then echo "  OK   $1"; else echo "  FAIL $1"; FAIL=1; fi; }
checka ./usr/bin/bash
checka ./usr/bin/flatpak
checka ./usr/bin/bwrap
checka ./usr/bin/apt-get
checka ./etc/os-release
checka ./etc/ssl/certs/ca-certificates.crt
checka ./usr/local/lib/linx/firstboot.sh
checka ./usr/local/lib/linx/flatpak-search.sh
checka ./usr/local/lib/linx/flatpak-install.sh
checka ./usr/local/lib/linx/display-start.sh
checka ./usr/local/lib/linx/display-stop.sh
checka ./usr/local/share/linx/flathub.flatpakrepo
checka ./bin
rm -f "$LIST"
[ "$FAIL" = "0" ] || { echo "TARBALL_VERIFY_FAILED"; exit 1; }

ls -la "$TAROUT"
echo "BUILD_OK_${VARIANT}_${ARCH}"
