# ovpn-shell.sh - the `px` command.
#
# Sourced, never run. Everything here changes the shell that calls it, and a
# script cannot do that to its parent: run this file and the exports die with
# it, half a second later, having changed nothing. That is the whole reason
# `ovpn proxy env` prints the exports instead of applying them, and why this
# file exists to eval what it prints.
#
#     . /path/to/ovpn-pin/ovpn-shell.sh
#
# `ovpn install` puts that line in your rc file. Works in bash and zsh.
#
#     px            send this shell through the proxy that is running
#     px 8899       through the one on that port
#     px off        stop
#     px status     what this shell is set to now, and whether it still works

px() {
    local out rc

    case ${1:-} in
        off|stop|down)
            eval "$(command ovpn proxy env --off)"
            return 0
            ;;
        status|show|'?')
            command ovpn proxy env --show
            return $?
            ;;
        -h|--help|help)
            command ovpn proxy env --show >/dev/null 2>&1
            printf '  px            send this shell through the running proxy\n'
            printf '  px 8899       through the one on that port\n'
            printf '  px off        stop sending it\n'
            printf '  px status     what this shell is set to now\n'
            return 0
            ;;
        '')
            out=$(command ovpn proxy env)
            rc=$?
            ;;
        *[!0-9]*)
            printf 'px: %s is not a port, "off", or "status"\n' "$1" >&2
            printf '    px  |  px 8899  |  px off  |  px status\n' >&2
            return 2
            ;;
        *)
            out=$(command ovpn proxy env --port "$1")
            rc=$?
            ;;
    esac

    # Only the exports come back on stdout; the reason it failed has already
    # been printed to stderr, where the person could see it. So there is
    # nothing to say here, and nothing to eval either.
    [ "$rc" -eq 0 ] || return "$rc"
    eval "$out"
}
