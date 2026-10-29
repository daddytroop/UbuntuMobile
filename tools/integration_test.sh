#!/bin/bash
# ============================================================================
# End-to-end test of the container, on the build machine.
#
# This runs the EXACT command line that LinuxRuntime.buildGuestCommand()
# produces against a real Ubuntu 26.04 rootfs, with a real proot.
#
# It exists because the failures this app keeps hitting are invisible to the
# unit tests:
#
#   * options that the Termux build of proot rejects but that upstream
#     proot accepts (a bare "--" for instance);
#   * guest programs that depend on something proot changes - Ubuntu
#     26.04's Rust coreutils works out which utility to run from AT_EXECFN,
#     and proot sets AT_EXECFN to the path of its own loader.
#
# Usage: tools/integration_test.sh [arch] [rootfs-directory]
# ============================================================================
set -uo pipefail

ARCH="${1:-amd64}"
OUT="${LINX_OUT:-/opt/ubuntubuild/out}"
BUILD="${LINX_BUILD:-/opt/ubuntubuild/build-lite-$ARCH}"
PROOT="${PROOT_HOST_PREFIX:-/opt/proot-host}/bin/proot"
LOADER="${PROOT_HOST_PREFIX:-/opt/proot-host}/bin/loader"

FAILURES=0
pass() { echo "  ok    $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# --------------------------------------------------------------- the rootfs
ROOT="$BUILD"
if [ ! -d "$ROOT" ]; then
  TARBALL="$OUT/ubuntu-26.04-lite-$ARCH.tar.gz"
  [ -f "$TARBALL" ] || { echo "no rootfs: build it with tools/build_rootfs.sh $ARCH lite"; exit 1; }
  ROOT="${TMPDIR:-/tmp}/ubuntumobile-it-$ARCH"
  echo "=== extracting $TARBALL ==="
  rm -rf "$ROOT"; mkdir -p "$ROOT"
  tar -xzf "$TARBALL" -C "$ROOT"
fi

# ----------------------------------------------------------------- the proot
if [ ! -x "$PROOT" ] || [ ! -x "$LOADER" ]; then
  echo "=== building proot for the host ==="
  "$(dirname "${BASH_SOURCE[0]}")/build_proot_host.sh" || exit 1
fi

export PROOT_LOADER="$LOADER"
export PROOT_LOADER_32="$LOADER"
export PROOT_NO_SECCOMP=1
export PROOT_TMP_DIR="${TMPDIR:-/tmp}"

# The guest command line, mirroring LinuxRuntime.baseArgsFor().
GUEST_ARGS=(
  -r "$ROOT"
  -0
  -b /dev
  -b /proc
  -b /sys
  -b /data
  -b /sdcard
  -b /storage/emulated/0
  -w /root
)

# Run a command in the guest, exactly like the app does.
#
# proot writes warnings about bindings that do not exist on this machine
# (/data, /sdcard and /storage/emulated/0 only exist on a phone). Those
# are not failures, so they are filtered out; errors are kept.
guest() {
  "$PROOT" "${GUEST_ARGS[@]}" /bin/bash -lc "$1" 2>&1 | grep -v '^proot warning:'
}

echo "=== 1. the container starts ==="
out=$(guest 'echo LINUX_SMOKE_OK; uname -sr; head -1 /etc/os-release')
echo "$out" | sed 's/^/    /'
if echo "$out" | grep -q LINUX_SMOKE_OK; then pass "the container starts"; else fail "the container does not start"; fi
if echo "$out" | grep -q 'Ubuntu 26.04'; then pass "it is Ubuntu 26.04"; else fail "wrong release"; fi
if echo "$out" | grep -qi 'unknown program\|Permission denied'; then
  fail "proot reported an error: $(echo "$out" | grep -i 'unknown program\|Permission denied' | head -1)"
fi

echo "=== 2. the coreutils work ==="
out=$(guest 'ls /etc/hostname >/dev/null && cat /etc/hostname >/dev/null && echo TOOLS_OK')
if echo "$out" | grep -q TOOLS_OK; then pass "ls, cat and friends work"; else fail "coreutils broken: $out"; fi

echo "=== 3. no program reports an unknown name ==="
out=$(guest 'for c in ls cat env id uname head sed grep tar find ps df; do $c --version >/dev/null 2>&1 || echo "BROKEN:$c"; done')
if [ -z "$out" ]; then pass "every core tool runs"; else fail "broken tools: $out"; fi

echo "=== 4. the toolchain the app needs is present ==="
out=$(guest 'for c in bash sh apt-get flatpak bwrap python3 git curl xdotool Xvnc; do command -v $c >/dev/null || echo "MISSING:$c"; done')
if [ -z "$out" ]; then pass "the toolchain is complete"; else fail "missing tools: $out"; fi

echo "=== 5. Flathub is configured ==="
out=$(guest 'bash /usr/local/lib/linx/firstboot.sh 2>&1 | tail -2')
if echo "$out" | grep -q FIRSTBOOT_OK; then pass "the firstboot script succeeds"; else fail "firstboot failed: $out"; fi
out=$(guest 'flatpak remotes 2>/dev/null')
if echo "$out" | grep -q flathub; then pass "the Flathub remote is registered"; else fail "no Flathub remote: $out"; fi

echo "=== 6. the container can resolve names ==="
out=$(guest 'cat /etc/resolv.conf')
if echo "$out" | grep -q nameserver; then pass "the resolver is configured"; else fail "no resolver: $out"; fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "INTEGRATION_TEST_PASSED"
  exit 0
fi
echo "INTEGRATION_TEST_FAILED ($FAILURES)"
exit 1
