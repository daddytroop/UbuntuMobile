# Help for install lines typed into a terminal. Sourced by interactive
# shells only (from /etc/bash.bashrc and /etc/profile.d, which the app
# sets up): scripts get the real commands, untouched.
#
#   flatpak install NAME   plain names and missing apps, through linx-pkg
#   apt install NAME       when it fails: what the name is in Ubuntu
#   an unknown command     whether Ubuntu has it, and how to install it
case "$-" in
  *i*)
    flatpak() {
      if [ "${1:-}" = install ]; then
        /usr/local/bin/linx-pkg flatpak "$@"
      else
        command flatpak "$@"
      fi
    }
    apt() {
      command apt "$@" && return 0
      local rc=$?
      [ "${1:-}" = install ] && /usr/local/bin/linx-pkg --hint "${@:2}"
      return "$rc"
    }
    command_not_found_handle() {
      /usr/local/bin/linx-pkg --missing "$1"
      return 127
    }
    ;;
esac
