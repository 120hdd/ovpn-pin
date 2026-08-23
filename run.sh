#!/usr/bin/env bash
#
# run.sh - the same menu run.cmd gives on Windows, for the two Linux scripts.
#
# Everything here is a shortcut for a flag you could type yourself, and the
# flags it builds are printed before each run - so this is a way of learning
# the command line rather than a replacement for it. Arguments are passed
# straight through, so `./run.sh --sweep --one-per` skips the menu entirely.

set -uo pipefail
cd "$(dirname "$(readlink -f "$0")")" || exit 1

PIN=./resolve-ovpn-remote.sh
CON=./ovpn-connect.sh

for f in "$PIN" "$CON"; do
    [ -f "$f" ] || { printf '\n  [fail] %s is missing - keep run.sh in the ovpn-pin folder.\n\n' "$f"; exit 1; }
done

if [ -t 1 ]; then
    C_OFF=$'\033[0m'; C_CYAN=$'\033[36m'; C_DCYAN=$'\033[36;2m'
    C_GRAY=$'\033[90m'; C_WHITE=$'\033[97m'; C_RED=$'\033[31m'
else
    C_OFF=''; C_CYAN=''; C_DCYAN=''; C_GRAY=''; C_WHITE=''; C_RED=''
fi

head_() { printf '\n%s  %s%s\n%s  %s%s\n' "$C_CYAN" "$1" "$C_OFF" \
          "$C_DCYAN" "$(printf '%*s' "${#1}" '' | tr ' ' '-')" "$C_OFF"; }
info()  { printf '         %s%s%s\n' "$C_GRAY" "$*" "$C_OFF"; }
bad()   { printf '%s  [fail] %s%s\n' "$C_RED" "$C_OFF" "$*"; }
ask()   { printf '  %s%s%s ' "$C_GRAY" "$1" "$C_OFF"; }

# Print what it is about to do before doing it. The point is that you can see
# the flags your answers turned into, and type them yourself next time.
#
# --print-only stops there and runs nothing, which is the whole menu turned
# into a lookup table: answer the questions, keep the command.
PRINT_ONLY=0
run() {
    printf '\n%s  $ %s%s\n\n' "$C_DCYAN" "$*" "$C_OFF"
    [ "$PRINT_ONLY" -eq 1 ] && return 0
    "$@"
    local rc=$?
    [ "$rc" -eq 0 ] || { printf '\n'; bad "that exited with code $rc"; }
    return 0
}

options() {
    cat <<EOF

  Options
  -------
  Everything below works on menu item 8, and on the run.sh line itself - so
      ./run.sh --sweep --one-per --landlord M247
  does that one job and nothing else.

  Pinning - resolve-ovpn-remote.sh
    -p, --path X       a .ovpn file, or a folder of them. Default configs/
    -o, --out-dir X    where the fixed copies go. Default pinned/
    --proxy URL        proxy for the DoH lookups, e.g. http://127.0.0.1:10808
    --doh-via MODE     auto (default), direct, proxy
    --resolver X       cloudflare (default) or google
    -n, --max-ips N    most files to write per config. Default 4
    --sync             re-resolve the hostnames already pinned from
    --who              name the company each pinned address is rented from
    --check-cloudflare judge the exit you are on now; pins nothing
    --site a,b         extra sites for that check
    --no-test          skip the reachable check
    --add-auth         add auth-user-pass to configs that have none
    --no-auth          ignore the credentials for one run

  Connecting and judging - ovpn-connect.sh
    [config]           a number, a filename, or part of one
    --status           what is up, and where traffic leaves from
    --stop             tunnel down, proxy back, kill switch removed
    --switch NAME      stop what is up, then connect NAME
    --sweep [NAME]     connect each config in turn and judge its exit
    --one-per          one address per location, not all of them
    --one-per-landlord one address per hosting company. About twenty tests
                       instead of a hundred and forty - run this first
    --site a,b         extra sites to test on each exit. Each one gets a
                       folder under sitetest/ with what actually served it
    --first N          stop after N of them
    --landlord A,B     only the ones rented from these hosting companies
    --pick-landlord    list the companies and pick by number
    --retest           sweep success/ instead of pinned/, dropping what
                       no longer connects
    --success-dir DIR  where the ones that connect are kept. Default success/
    --no-owner         skip the "rented from" lookup
    --pick             connect the best exit when the sweep is done
    --site a,b         the sites to test on each exit
    --set-dns          point the tunnel at the DNS the server pushes
    --kill-switch      drop everything that is not the tunnel while it is up
    --dns-check        is DNS going through the tunnel, or still forged?
    --dry-run          print the openvpn command, change nothing

EOF
}

# ----------------------------------------------------------------- actions --

do_pin_proxy() {
    printf '\n'
    info 'The proxy carries the DoH lookups only. Nothing about connecting later'
    info 'depends on it, because the address ends up written into the config.'
    info 'Common ports: v2rayN 10809, Clash 7890, Nekoray 2080, Hiddify 12334.'
    printf '\n'
    local proxy
    ask 'proxy [http://127.0.0.1:10808]:'
    read -r proxy
    [ -n "$proxy" ] || proxy=http://127.0.0.1:10808
    run "$PIN" --proxy "$proxy"
}

do_cloudflare() {
    printf '\n'
    info 'This measures the connection you have this second, so it only means'
    info 'anything while the VPN is up. Extra sites are optional - Cloudflare'
    info 'rules are per-customer, so a strict site can refuse an exit that'
    info "Cloudflare's own pages serve happily."
    printf '\n'
    local sites
    ask 'extra sites, comma separated (enter for none):'
    read -r sites
    if [ -n "$sites" ]; then run "$PIN" --check-cloudflare --site "$sites"
    else                     run "$PIN" --check-cloudflare
    fi
}

do_sweep() {
    local dir which sites lord percompany args=()

    printf '\n'
    info 'This connects to each config in turn and measures what the web does'
    info 'with that exit - the thing no amount of looking at your own line can'
    info 'tell you. Your connection drops in and out while it runs, and openvpn'
    info 'needs root, so it will ask for your password.'
    printf '\n'
    printf '  Which folder?\n\n'
    printf '    %s1%s  pinned/    everything you have. The full survey.\n' "$C_CYAN" "$C_OFF"
    printf '    %s2%s  success/   only the ones that connected last time,\n' "$C_CYAN" "$C_OFF"
    printf '                  re-tested. Much shorter, and the honest way to\n'
    printf '                  find out whether yesterday'"'"'s good list is still\n'
    printf '                  good. Anything that has stopped connecting is\n'
    printf '                  dropped from it.\n\n'
    ask 'folder [1]:'
    read -r dir
    [ "$dir" = 2 ] && args+=(--retest)

    printf '\n'
    printf '  Three ways to answer the next question:\n\n'
    printf '    enter        one address per location. The fast way round, and\n'
    printf '                 the one to start with. Re-testing success/, it\n'
    printf '                 means all of it - that list is short already.\n'
    printf '    a name       part of one - de-, us-lax, nl - and every address\n'
    printf '                 of the configs that match gets tested.\n'
    printf '    all          every pinned address there is. Hours, not minutes.\n\n'
    ask 'which ones? (enter, a name, or all):'
    read -r which

    case ${which,,} in
        ''|' ')  [ "$dir" = 2 ] || args+=(--one-per) ;;
        all|'*') ;;
        *)       args+=("$which") ;;
    esac

    ask 'extra sites to test, comma separated (enter for none):'
    read -r sites
    [ -n "$sites" ] && args+=(--site "$sites")

    printf '\n'
    info 'You can also throw out whole hosting companies first. Your addresses'
    info 'are rented from about twenty of them, and one company is near enough'
    info 'one address as far as being blocked goes - so choosing a few to test'
    info 'is usually a better half hour than testing everything.'
    printf '\n'
    ask 'choose by landlord first? [y/N]:'
    read -r lord
    [ "${lord,,}" = y ] && args+=(--pick-landlord)

    printf '\n'
    info 'And how many addresses out of each company? One apiece is the coarse,'
    info 'fast answer - about twenty tests, ten minutes, and it tells you whose'
    info 'addresses still work. One per location is the normal answer and takes'
    info 'around seven times longer. Coarse first, then sweep the survivors.'
    printf '\n'
    ask 'one address per company? [y/N]:'
    read -r percompany
    # Narrows whatever is left, including the "all" and name answers above.
    # It also supersedes one-per-location: sending both works, since the sweep
    # takes the narrower of the two, but it says so when you do - and from the
    # menu that note would appear on every single run.
    if [ "${percompany,,}" = y ]; then
        local a filtered=()
        for a in "${args[@]}"; do [ "$a" = --one-per ] || filtered+=("$a"); done
        args=("${filtered[@]}" --one-per-landlord)
    fi

    run "$CON" --sweep "${args[@]}"
}

do_connect() {
    printf '\n'
    info 'A menu of the pinned configs, with what the last sweep made of each.'
    info 'Pick a number there, or type part of a name at the prompt below.'
    printf '\n'
    local sel
    ask 'which one? (enter for the list):'
    read -r sel
    if [ -n "$sel" ]; then run "$CON" "$sel"
    else                  run "$CON"
    fi
}

do_open() {
    if [ ! -d pinned ]; then
        printf '\n'
        bad 'no pinned folder yet - run 1 first.'
        return 0
    fi
    if command -v xdg-open >/dev/null 2>&1; then
        run xdg-open pinned
    else
        printf '\n'
        info "no xdg-open here, so: $(pwd)/pinned"
    fi
}

do_custom() {
    options
    local opts
    ask 'options:'
    read -r opts
    [ -n "$opts" ] || return 0
    # Which script gets them is decided by what was asked for, so that
    # --sweep and --sync do not need to be remembered as belonging to
    # different files.
    case " $opts " in
        *' --sweep'*|*' --status'*|*' --stop'*|*' --switch'*|*' --dns-check'*|\
        *' --pick'*|*' --retest'*|*' --landlord'*|*' --one-per'*|*' --kill-switch'*|\
        *' --set-dns'*|*' --dry-run'*|*' --supervise'*)
            # shellcheck disable=SC2086
            run "$CON" $opts ;;
        *)
            # shellcheck disable=SC2086
            run "$PIN" $opts ;;
    esac
}

menu() {
    printf '\n%s  ovpn-pin%s\n' "$C_WHITE" "$C_OFF"
    printf '%s  ========%s\n\n' "$C_DCYAN" "$C_OFF"
    cat <<EOF
  A downloaded .ovpn points at a name. On a censored line your resolver
  answers that name with a fake address, so OpenVPN dials nowhere. This
  looks the name up over DNS-over-HTTPS instead and writes the real address
  into the config. Your originals in configs/ are never touched; the fixed
  copies come out in pinned/.

    1  Get the real IPs and write the fixed configs
         Reads every file in configs/, looks it up over DoH, writes pinned/,
         then says which addresses answer. Start here.

    2  The same, but do the lookups through a proxy
         Only if 1 could not look anything up - DoH itself is blocked, not
         just ordinary DNS. The proxy is used for the lookup, not the tunnel.

    3  Judge the VPN you are connected to right now
         Asks Cloudflare what it makes of this exit. Some VPN addresses are
         refused by half the web while the tunnel is perfectly fine.

    4  Test every location, one after another
         Connects to each config for real, judges its exit, drops it, next.
         What comes up is copied to success/ with the time it took, and to
         success/landlord/ with the hosting company and country as well.

    5  Who owns these addresses
         Names the company each server is rented from - M247, Datacamp,
         Clouvider - and groups them by it. Connects to nothing.

    6  Connect to one of them
    7  What is connected, and where traffic leaves from
    8  Run it with options you type yourself
    h  Show every option
    0  Quit

EOF
}

# ------------------------------------------------------------------- main --

# Taken off the front rather than matched in the case below, which evaluates
# its word once and would not notice the shift.
if [ "${1:-}" = --print-only ]; then PRINT_ONLY=1; shift; fi

case ${1:-} in
    -h|--help|/h|/\?)
        options
        exit 0 ;;
    '') ;;
    *)  # Anything else is for the scripts themselves.
        case " $* " in
            *' --sweep'*|*' --status'*|*' --stop'*|*' --switch'*|*' --dns-check'*|\
            *' --retest'*|*' --landlord'*|*' --one-per'*|*' --kill-switch'*|\
            *' --set-dns'*|*' --dry-run'*|*' --supervise'*|*' --install-service'*)
                exec "$CON" "$@" ;;
            *)  exec "$PIN" "$@" ;;
        esac ;;
esac

# No "must be a terminal" check here on purpose. ovpn-connect.sh has one,
# because a config named by nobody on a pipe is a mistake worth catching; this
# is a menu, and answers piped into a menu are answers. It stops on EOF.

while true; do
    menu
    ask 'choose [1]:'
    read -r choice || exit 0
    [ -n "$choice" ] || choice=1

    case ${choice,,} in
        1) run "$PIN" ;;
        2) do_pin_proxy ;;
        3) do_cloudflare ;;
        4) do_sweep ;;
        5) run "$PIN" --who ;;
        6) do_connect ;;
        7) run "$CON" --status ;;
        8) do_custom ;;
        h) options ;;
        0|q) exit 0 ;;
        *) continue ;;
    esac

    printf '\n  %s------------------------------------------------------------------%s\n' "$C_DCYAN" "$C_OFF"
    ask 'enter for the menu, q to quit:'
    read -r back || exit 0
    [ "${back,,}" = q ] && exit 0
done
