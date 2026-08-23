#!/usr/bin/env bash
#
# ovpn-connect.sh - brings up one of the pinned configs, and gets the proxy
# out of the way the moment it is no longer needed.
#
# The thing worth understanding before reading further:
#
#     A tunnel dialled through a proxy rides on that proxy's TCP connection
#     for its whole life. The proxy is not "the initial connect" - it is the
#     transport. Close it afterwards and the tunnel goes with it.
#
# So the way to end up with the proxy switched off is to never need it for the
# tunnel in the first place. Where the block is on DNS - which is what this
# repo is about - the proxy is only needed for the DoH lookup at pinning time.
# Once resolve-ovpn-remote.sh has written the address into the config, OpenVPN
# dials the IP itself and the proxy has no part in it, so it can be shut off
# and nothing goes through two tunnels.
#
# This script measures that rather than assuming it: it probes the pinned
# address directly, with the proxy bypassed, and only then decides. If the
# address answers, it connects with no proxy at all and turns the system proxy
# off once the tunnel is up. If it does not, it says so and offers the three
# ways out instead of quietly doing the expensive one.
#
# Requires: bash 4+, openvpn, curl, iproute2, sudo.

set -uo pipefail

VERSION=1.2.0
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SELF=$(basename -- "${BASH_SOURCE[0]}")

# shellcheck source=ovpn-lib.sh
. "$ROOT/ovpn-lib.sh" 2>/dev/null || {
    printf '\n  [fail] ovpn-lib.sh is missing from %s\n\n' "$ROOT" >&2
    exit 1
}

STATE=$ROOT/.state
PIDFILE=$STATE/openvpn.pid
LOGFILE=$STATE/openvpn.log
CURFILE=$STATE/current
MODEFILE=$STATE/mode
ROUTEFILE=$STATE/proxy-route
GSETTINGS_FILE=$STATE/proxy-mode

# How long to wait for the handshake before calling it a failure, and how long
# a single reachability probe may take. The probe is short on purpose: it runs
# before anything else and a dead address should not cost twenty seconds.
WAIT=45
PROBE_TIMEOUT=8


#----------------------------------------------------------------------- help

usage() {
    cat <<EOF
  $SELF $VERSION
  connects one of the pinned configs, then gets the proxy out of the way

  usage: ./$SELF [config] [options]

    (no argument)          a numbered menu of pinned/
    de-fra                 a number, a filename, or part of one
    --switch NAME          stop whatever is up and connect NAME
    --status               what is up, and where traffic is leaving from
    --stop                 bring the tunnel down and put the proxy back

  judging the exits

        --sweep [NAME]     connect each pinned config in turn, ask Cloudflare
                           what it makes of that exit, disconnect, and report.
                           NAME narrows it to the files that match.
        --site HOST[,HOST] the sites you actually care about, tested on each
        --pick             connect the best exit when the sweep is done
        --one-per          one address per location, not all of them
        --one-per-landlord one address per hosting company. Coarser and much
                           faster than --one-per: about twenty tests, not a
                           hundred and forty. The one to run first
        --one-per-landlord-location
                           one address per company per location. A company is
                           spread over dozens of places and they do not share
                           a fate, so this asks about each of them separately:
                           nine locations of HostRoyale, nine tests
        --first N          stop after N of them
        --landlord A,B     only the configs rented from these hosting
                           companies: --landlord M247,CDN77 or AS9009
        --pick-landlord    list the companies behind these configs and sweep
                           only the ones you choose
        --retest           sweep the success folder instead of the pinned one:
                           re-test what worked, and drop what no longer does
        --success-dir DIR  where the ones that connect are kept. Default
                           success/, with a landlord/ folder inside it
        --sitetest-dir DIR where the per-site folders go. Default sitetest/:
                           one folder per --site host, holding the configs
                           that actually served it
        --no-owner         do not look up who owns each exit
        --dns-check        is DNS going through the tunnel, or still being
                           answered - and forged - by your line?

  the proxy

        --via MODE         auto (default) | direct | proxy
                           auto   probe the address, use the proxy only if it
                                  cannot be dialled directly
                           direct never use the proxy; fail if it is needed
                           proxy  always dial through the proxy
        --via-proxy        = --via proxy
        --fallback MODE    when auto finds the address unreachable:
                           ask (default on a terminal) | proxy | next | stop
        --next             = --fallback next
        --no-proxy-off     leave the system proxy alone after connecting

  other

        --set-dns          point the tunnel interface at the DNS the server
                           pushed (needs systemd-resolved)
        --kill-switch      block everything that is not the tunnel while it is
                           up, so a drop cannot leak. Removed on --stop.
        --kill-switch-off  remove a kill switch left behind, and exit
        --install-service  write a systemd unit that connects at boot
        --supervise        connect and stay in the foreground, exiting when
                           the tunnel dies. What the unit runs.
        --timeout N        seconds to wait for the handshake (default $WAIT)
        --dry-run          print what would run, change nothing
    -h, --help
    -V, --version

  Credentials come from the pinned config itself - resolve-ovpn-remote.sh
  writes them there from .env. Nothing is typed at connect time.

EOF
}


#---------------------------------------------------------------------- state

state_init() { mkdir -p -- "$STATE" 2>/dev/null || die "cannot create $STATE"; }

pid_alive() {
    local pid=$1
    [ -n "$pid" ] || return 1
    if [ -d /proc ]; then
        [ -d "/proc/$pid" ] || return 1
        # Guard against a recycled pid: it has to still be openvpn.
        grep -qa openvpn "/proc/$pid/cmdline" 2>/dev/null || return 1
        return 0
    fi
    ps -p "$pid" -o comm= 2>/dev/null | grep -q openvpn
}

running_pid() {
    local pid
    [ -f "$PIDFILE" ] || return 1
    pid=$(tr -dc '0-9' < "$PIDFILE" 2>/dev/null)
    pid_alive "$pid" || return 1
    printf '%s' "$pid"
}

# The log belongs to root. Readable in practice, but do not bet the run on it.
log_cat() {
    [ -f "$LOGFILE" ] || return 1
    cat -- "$LOGFILE" 2>/dev/null || $SUDO cat -- "$LOGFILE" 2>/dev/null
}

tunnel_dev() {
    local d
    d=$(log_cat | grep -oE 'TUN/TAP device [a-z0-9]+' | tail -n1 | awk '{print $3}')
    if [ -z "$d" ] && command -v ip >/dev/null 2>&1; then
        d=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*[[:space:]]dev[[:space:]]\+\([^[:space:]]*\).*/\1/p' | head -n1)
    fi
    printf '%s' "$d"
}

# What Cloudflare says about the address we are leaving from. Never through a
# proxy: the question is what the default route does.
egress_line() {
    local body ip loc colo
    body=$(curl -sS --noproxy '*' --max-time 10 \
                -A 'Mozilla/5.0' https://www.cloudflare.com/cdn-cgi/trace 2>/dev/null) || return 1
    ip=$(sed -n 's/^ip=//p'   <<<"$body" | head -n1)
    loc=$(sed -n 's/^loc=//p' <<<"$body" | head -n1)
    colo=$(sed -n 's/^colo=//p' <<<"$body" | head -n1)
    [ -n "$ip" ] || return 1
    printf '%s%s%s' "$ip" "${loc:+ in $loc}" "${colo:+, via $colo}"
}


#---------------------------------------------------------------------- proxy

# Only the system proxy setting is touched. The client itself keeps running:
# nothing is being handed to it any more, which is the whole point, and you
# will want it back the moment the tunnel comes down.
proxy_off() {
    local acted=0 prev

    if command -v gsettings >/dev/null 2>&1; then
        prev=$(gsettings get org.gnome.system.proxy mode 2>/dev/null | tr -d "'")
        if [ -n "$prev" ] && [ "$prev" != none ]; then
            printf '%s\n' "$prev" > "$GSETTINGS_FILE"
            if gsettings set org.gnome.system.proxy mode none 2>/dev/null; then
                ok "system proxy off (was '$prev') - traffic goes through the tunnel only"
                acted=1
            fi
        elif [ "$prev" = none ]; then
            info "system proxy was already off"
            acted=1
        fi
    fi

    if [ -n "$PROXY_OFF_CMD" ]; then
        if bash -c "$PROXY_OFF_CMD"; then ok "ran OVPN_PROXY_OFF_CMD"
        else warn "OVPN_PROXY_OFF_CMD failed"
        fi
        acted=1
    fi

    if [ "$acted" -eq 0 ]; then
        info 'nothing to switch off - no system proxy setting found and no'
        info 'OVPN_PROXY_OFF_CMD in .env. If your browser is pointed straight'
        info "at $PROXY, unset it there or its traffic goes through both."
    fi
}

proxy_on() {
    local prev
    if [ -f "$GSETTINGS_FILE" ] && command -v gsettings >/dev/null 2>&1; then
        prev=$(cat "$GSETTINGS_FILE")
        gsettings set org.gnome.system.proxy mode "$prev" 2>/dev/null \
            && info "system proxy put back to '$prev'"
        rm -f -- "$GSETTINGS_FILE"
    fi
    [ -n "$PROXY_ON_CMD" ] && bash -c "$PROXY_ON_CMD" >/dev/null 2>&1
    return 0
}

resolve_proxy() {
    if [ -n "$PROXY" ]; then return 0; fi
    info 'looking for a local proxy...'
    PROXY=$(find_proxy) || return 1
    return 0
}

# Which addresses the proxy client itself is talking to. In proxied mode those
# connections must keep going out the physical interface: the tunnel is
# carried by them, so routing them into the tunnel would have it strangle
# itself. OpenVPN cannot work this out on its own - as far as it knows its
# server is 127.0.0.1.
proxy_upstream_ips() {
    local port=$1 pid ip
    [ -n "$PROXY_UPSTREAM" ] && { printf '%s\n' "$PROXY_UPSTREAM"; return 0; }
    command -v ss >/dev/null 2>&1 || return 1

    pid=$(ss -tlnpH "sport = :$port" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -n1 | cut -d= -f2)
    [ -n "$pid" ] || return 1

    ss -tnpH state established 2>/dev/null | grep -F "pid=$pid," | awk '{print $4}' \
        | sed 's/:[0-9]*$//' | tr -d '[]' \
        | while IFS= read -r ip; do
              is_ip_literal "$ip" || continue
              is_reserved_ip "$ip" && continue
              printf '%s\n' "$ip"
          done | sort -u
}

pin_upstream_routes() {
    local port=$1 ip gw dev line
    : > "$ROUTEFILE"
    while IFS= read -r ip; do
        [ -n "$ip" ] || continue
        line=$(ip route get "$ip" 2>/dev/null | head -n1)
        gw=$(sed -n 's/.*[[:space:]]via[[:space:]]\+\([^[:space:]]*\).*/\1/p' <<<"$line")
        dev=$(sed -n 's/.*[[:space:]]dev[[:space:]]\+\([^[:space:]]*\).*/\1/p' <<<"$line")
        [ -n "$dev" ] || continue
        if $SUDO ip route add "$ip/32" ${gw:+via "$gw"} dev "$dev" 2>/dev/null; then
            printf '%s\n' "$ip" >> "$ROUTEFILE"
            info "pinned a direct route for the proxy's own server $ip"
        fi
    done < <(proxy_upstream_ips "$port")

    if [ ! -s "$ROUTEFILE" ]; then
        rm -f -- "$ROUTEFILE"
        warn "could not work out which server your proxy talks to."
        info 'If the tunnel comes up and then stalls, that is why: the proxy'
        info 'traffic is being routed into the tunnel it carries. Set'
        info 'OVPN_PROXY_UPSTREAM=<ip of your v2ray server> in .env.'
    fi
}

unpin_upstream_routes() {
    local ip
    [ -f "$ROUTEFILE" ] || return 0
    while IFS= read -r ip; do
        [ -n "$ip" ] && $SUDO ip route del "$ip/32" 2>/dev/null
    done < "$ROUTEFILE"
    rm -f -- "$ROUTEFILE"
}


#-------------------------------------------------------------------- configs

CFG_FILE=(); CFG_NAME=(); CFG_IP=(); CFG_PORT=(); CFG_PROTO=()

list_configs() {
    local f
    CFG_FILE=(); CFG_NAME=(); CFG_IP=(); CFG_PORT=(); CFG_PROTO=()
    for f in "$OUT_DIR"/*.ovpn; do
        [ -e "$f" ] || continue
        read_config_lines "$f"
        scan_remotes
        [ ${#R_INDEX[@]} -gt 0 ] || continue
        CFG_FILE+=("$f")
        CFG_NAME+=("$(basename -- "$f")")
        CFG_IP+=("${R_HOST[0]}")
        CFG_PORT+=("$(remote_port "${R_TAIL[0]}")")
        CFG_PROTO+=("$(remote_proto "${R_TAIL[0]}")")
    done
    [ ${#CFG_FILE[@]} -gt 0 ]
}

# What the last sweep (or --check-cloudflare) made of this exit, so the menu
# can say something useful without measuring anything.
last_verdict() {
    local row
    row=$(tsv_get "$(exits_tsv)" "$1") || return 1
    printf '%s' "$(tsv_field "$row" 3)"
}

print_configs() {
    local i cur='' mark v age tag
    [ -f "$CURFILE" ] && cur=$(cat "$CURFILE")
    for i in "${!CFG_FILE[@]}"; do
        mark=''
        [ "${CFG_FILE[$i]}" = "$cur" ] && mark="  ${C_GREEN}[current]${C_OFF}"
        tag=''
        if v=$(last_verdict "${CFG_NAME[$i]}"); then
            case $v in
                clean)  tag="  ${C_GREEN}clean${C_OFF}" ;;
                partly) tag="  ${C_YELLOW}partly${C_OFF}" ;;
                dirty)  tag="  ${C_RED}flagged${C_OFF}" ;;
                *)      tag="  ${C_GRAY}$v${C_OFF}" ;;
            esac
        fi
        printf '   %s%2d%s  %-38s %s:%s %s%s%s\n' \
            "$C_CYAN" "$((i + 1))" "$C_OFF" "${CFG_NAME[$i]}" \
            "${CFG_IP[$i]}" "${CFG_PORT[$i]}" "${CFG_PROTO[$i]}" "$tag" "$mark"
    done

    # Addresses go stale. Say so once, rather than after the connection has
    # already failed for a reason that looks like something else.
    age=$(pin_age_days "${CFG_FILE[0]}" 2>/dev/null) || return 0
    if [ -n "$age" ] && [ "$age" -ge 7 ]; then
        printf '\n'
        info "these were pinned $age days ago - ./resolve-ovpn-remote.sh --sync for fresh addresses"
    fi
}

# A number, a whole filename, or enough of one to be unambiguous. An ambiguous
# argument is shown and refused rather than guessed at.
select_config() {
    local sel=$1 i hits=()

    if [[ $sel =~ ^[0-9]+$ ]] && [ "$sel" -ge 1 ] && [ "$sel" -le ${#CFG_FILE[@]} ]; then
        SEL_IDX=$((sel - 1)); return 0
    fi

    local low=${sel,,}
    for i in "${!CFG_NAME[@]}"; do
        local n=${CFG_NAME[$i],,}
        if [ "$n" = "$low" ] || [ "${n%.ovpn}" = "$low" ]; then SEL_IDX=$i; return 0; fi
    done
    for i in "${!CFG_NAME[@]}"; do
        local n=${CFG_NAME[$i],,}
        [[ $n == *"$low"* ]] && hits+=("$i")
    done

    case ${#hits[@]} in
        1) SEL_IDX=${hits[0]}; return 0 ;;
        0) head_ 'No such config'
           info "nothing in $OUT_DIR matches '$sel'. What is there:"
           print_configs
           printf '\n'
           exit 1 ;;
        *) head_ 'That matches more than one'
           local h
           for h in "${hits[@]}"; do
               printf '   %-38s %s:%s %s\n' "${CFG_NAME[$h]}" "${CFG_IP[$h]}" "${CFG_PORT[$h]}" "${CFG_PROTO[$h]}"
           done
           printf '\n'
           info 'Say which one - a number from the menu is unambiguous.'
           printf '\n'
           exit 1 ;;
    esac
}

menu() {
    local choice
    [ -t 0 ] || die "no config named and this is not a terminal. Name one: ./$SELF <config>"
    head_ "Pinned configs (${#CFG_FILE[@]})"
    print_configs
    printf '\n'
    printf '   %spick a number, or q to quit:%s ' "$C_GRAY" "$C_OFF"
    read -r choice
    [ "$choice" = q ] || [ "$choice" = Q ] && { printf '\n'; exit 0; }
    [ -n "$choice" ] || { printf '\n'; exit 0; }
    select_config "$choice"
}


#------------------------------------------------------------------ preflight

# The one measurement the whole design turns on: can this machine dial the
# pinned address itself, with no proxy in the way?
probe_direct() {
    local i=$1
    if [[ ${CFG_PROTO[$i]} == udp* ]]; then
        # A UDP port cannot be probed - OpenVPN drops any datagram without a
        # valid HMAC, so silence means "blocked" and "working" equally. And a
        # UDP config cannot go through an HTTP proxy at all, so there is
        # nothing to decide: it is direct or it is nothing.
        return 0
    fi
    tcp_reachable "${CFG_IP[$i]}" "${CFG_PORT[$i]}" "$PROBE_TIMEOUT"
}

# Used by --fallback next: the first pinned config that answers directly.
find_reachable() {
    local i
    for i in "${!CFG_FILE[@]}"; do
        [ "$i" -eq "$SEL_IDX" ] && continue
        [[ ${CFG_PROTO[$i]} == udp* ]] && continue
        if tcp_reachable "${CFG_IP[$i]}" "${CFG_PORT[$i]}" "$PROBE_TIMEOUT"; then
            printf '%s' "$i"; return 0
        fi
    done
    return 1
}

show_alternatives() {
    local i found=0
    info 'testing the other pinned files...'
    for i in "${!CFG_FILE[@]}"; do
        [ "$i" -eq "$SEL_IDX" ] && continue
        [[ ${CFG_PROTO[$i]} == udp* ]] && continue
        if tcp_reachable "${CFG_IP[$i]}" "${CFG_PORT[$i]}" "$PROBE_TIMEOUT"; then
            ok "${CFG_NAME[$i]}  ${CFG_IP[$i]}:${CFG_PORT[$i]}  answers directly"
            found=$((found + 1))
        fi
    done
    if [ "$found" -eq 0 ]; then
        bad 'none of the pinned addresses answer directly.'
        info 'Either they have gone stale - re-run ./resolve-ovpn-remote.sh -'
        info 'or your line blocks the addresses themselves and not just DNS,'
        info "in which case the tunnel has to ride the proxy: ./$SELF --via-proxy"
    fi
}

# Everything but the answer goes to stderr: the caller reads this one's
# stdout, and a prompt captured into the variable would be no answer at all.
ask_fallback() {
    local answer nodial=${1:-}
    {
        printf '\n'
        if [ "$nodial" = nodial ]; then
            info '  1  (not offered - the proxy cannot reach this address either)'
        else
            info '  1  connect through the proxy anyway (it stays up for the whole session)'
        fi
        info '  2  try the other pinned files and take one that answers directly'
        info '  3  stop here'
        printf '\n'
        printf '   %schoice [3]:%s ' "$C_GRAY" "$C_OFF"
    } >&2
    read -r answer
    case $answer in
        1) [ "$nodial" = nodial ] && printf 'stop' || printf 'proxy' ;;
        2) printf 'next' ;;
        *) printf 'stop' ;;
    esac
}


#-------------------------------------------------------------------- connect

# The auth file is a cache of what .env says, and until now the pinner was the
# only thing that ever wrote it. So changing a password in .env and connecting
# again quietly kept using the old one, and the server's rejection was the only
# hint - which points at the password rather than at the stale copy of it.
# Comparing the two here costs nothing, and .env is the side you edited.
#
# Content, not timestamps: an auth file touched after the .env edit (a chmod, a
# backup restored, a sync) would still be stale while looking newer.
refresh_auth_file() {
    local want dir verb=updated

    # Half a pair is a typo, not a decision, and silently falling back to the
    # cached file would hide it behind an auth failure later.
    if [ -z "$AUTH_USER" ] || [ -z "$AUTH_PASS" ]; then
        if [ -n "$AUTH_USER$AUTH_PASS" ]; then
            head_ 'Credentials'
            warn "only half the credentials are set in $ROOT/.env - both OVPN_USER and OVPN_PASS are needed"
            [ -f "$AUTH_FILE" ] && info "carrying on with $AUTH_FILE as it stands."
        fi
        return 0
    fi

    # Both sides of this comparison lose their trailing newlines the same way,
    # so a file that only differs by one is not rewritten every run.
    want=$(printf '%s\n%s\n' "$AUTH_USER" "$AUTH_PASS")
    [ -f "$AUTH_FILE" ] || verb=written
    if [ "$verb" = updated ] && [ "$(cat "$AUTH_FILE" 2>/dev/null)" = "$want" ]; then
        return 0
    fi

    dir=$(dirname -- "$AUTH_FILE")
    mkdir -p -- "$dir" || die "cannot create $dir"
    ( umask 077; printf '%s\n%s\n' "$AUTH_USER" "$AUTH_PASS" > "$AUTH_FILE" ) \
        || die "cannot write $AUTH_FILE - fix the permissions or set OVPN_AUTH_FILE to somewhere writable"
    chmod 600 -- "$AUTH_FILE" 2>/dev/null || true

    head_ 'Credentials'
    if [ "$verb" = written ]; then
        ok "$AUTH_FILE written from .env (mode 600)"
    else
        ok "$AUTH_FILE no longer matched .env - updated from it (mode 600)"
    fi
    info "user: $AUTH_USER"
}

# openvpn takes credentials from the config (the pinner wrote them there). A
# config still asking interactively cannot work under --daemon, so catch it
# here rather than letting it fail somewhere less legible.
#
# Reports the miss with a status rather than dying: this runs inside a command
# substitution, where an exit takes the subshell and leaves the caller going.
auth_args() {
    local f=$1
    grep -qE '^[[:space:]]*auth-user-pass[[:space:]]*$' "$f" || return 0
    [ -f "$AUTH_FILE" ] || return 1
    printf '%s\n%s\n' '--auth-user-pass' "$AUTH_FILE"
}

start_tunnel() {
    local i=$1 mode=$2 file=${CFG_FILE[$1]} cmd=() ph pp arg authargs

    cmd=(openvpn --config "$file" --daemon ovpn-pin
         --log "$LOGFILE" --writepid "$PIDFILE" --verb 3 --connect-retry-max 3)

    authargs=$(auth_args "$file") || die "$(basename -- "$file") asks for a username and password and no auth file exists.
         Put OVPN_USER and OVPN_PASS in $ROOT/.env and run this again - the auth
         file is written from them, no need to re-pin."
    while IFS= read -r arg; do [ -n "$arg" ] && cmd+=("$arg"); done <<<"$authargs"

    if [ "$mode" = proxy ]; then
        read -r ph pp <<<"$(split_proxy_url "$PROXY")"
        cmd+=(--http-proxy "$ph" "$pp")
    fi

    if [ "$DRY_RUN" -eq 1 ]; then
        head_ 'Dry run'
        info "mode: $mode"
        say ''
        say "  $SUDO ${cmd[*]}"
        say ''
        if [ "$mode" = direct ]; then
            info 'after the handshake: system proxy would be switched off.'
        else
            info 'after the handshake: proxy left alone - the tunnel needs it.'
        fi
        printf '\n'
        exit 0
    fi

    [ "$mode" = proxy ] && pin_upstream_routes "$pp"

    rm -f -- "$LOGFILE" 2>/dev/null || $SUDO rm -f -- "$LOGFILE"
    if ! $SUDO "${cmd[@]}"; then
        unpin_upstream_routes
        bad 'openvpn refused to start. Run it by hand to see why:'
        info "     sudo openvpn --config '$file'"
        return 1
    fi

    printf '%s\n' "$file" > "$CURFILE"
    printf '%s\n' "$mode" > "$MODEFILE"
}

# Translate the log rather than dumping it. Every one of these lines means
# something specific and actionable, and none of them says so itself.
#
# 0 up, 1 a dead end - stop it, 2 nothing yet but it may still arrive.
wait_for_up() {
    local waited=0 pid log
    while [ "$waited" -lt "$WAIT" ]; do
        log=$(log_cat)
        case $log in
            *'Initialization Sequence Completed'*) return 0 ;;
            *AUTH_FAILED*)
                bad 'the server refused the username and password.'
                info "Check OVPN_USER and OVPN_PASS in $ROOT/.env, then run"
                info './resolve-ovpn-remote.sh again to rewrite the auth file.'
                return 1 ;;
            *'TLS Error: TLS key negotiation failed'*|*'TLS handshake failed'*)
                bad 'no TLS answer from the server.'
                info 'The address answers TCP but not as an OpenVPN server - the'
                info 'usual sign of a stale pin or a middlebox. Re-run the pinner,'
                info 'or try the next pinned file.'
                return 1 ;;
            *'Connection refused'*|*'Network is unreachable'*|*'No route to host'*)
                bad 'the address is not answering any more.'
                info 'Re-run ./resolve-ovpn-remote.sh - providers move addresses.'
                return 1 ;;
        esac

        pid=$(running_pid) || {
            bad 'openvpn exited before the tunnel came up.'
            log_cat | tail -n 5 | while IFS= read -r l; do info "$l"; done
            return 1
        }
        sleep 1
        waited=$((waited + 1))
    done

    bad "no handshake after ${WAIT}s."
    log_cat | tail -n 5 | while IFS= read -r l; do info "$l"; done
    info "Still trying in the background. ./$SELF --stop to give up on it."
    return 2
}

report_up() {
    local i=$1 mode=$2 dev server eg
    dev=$(tunnel_dev)
    server=$(log_cat | grep -oE 'Peer Connection Initiated with \[AF_INET\][0-9.]+:[0-9]+' | tail -n1 | sed 's/.*\]//')

    head_ 'Up'
    ok "${CFG_NAME[$i]}"
    info "server:  ${server:-${CFG_IP[$i]}:${CFG_PORT[$i]}} (${CFG_PROTO[$i]}, $([ "$mode" = direct ] && echo 'direct - no proxy' || echo "through $PROXY"))"
    [ -n "$dev" ] && info "device:  $dev"

    if [ "$mode" = direct ]; then
        if [ "$NO_PROXY_OFF" -eq 1 ]; then
            info 'proxy:   left alone (--no-proxy-off)'
        else
            printf '\n'
            proxy_off
        fi
    else
        printf '\n'
        warn 'the proxy stays up: this tunnel is carried by it.'
        info 'Closing it now would drop the tunnel with it. Traffic is encrypted'
        info 'twice for as long as this connection lasts - the price of an'
        info 'address your line will not dial directly.'
    fi

    [ "$SET_DNS" -eq 1 ] && apply_dns "$dev"

    if [ "$KILL_SWITCH" -eq 1 ]; then
        printf '\n'
        local extra=''
        [ -f "$ROUTEFILE" ] && extra=$(tr '\n' ' ' < "$ROUTEFILE")
        killswitch_on "$dev" "${CFG_IP[$i]}" "$extra"
    fi

    eg=$(egress_line) && { printf '\n'; info "Cloudflare sees you as $eg"; }

    [ "$DNS_CHECK" -eq 1 ] && dns_check

    printf '\n'
    info 'Is this exit one Cloudflare will actually serve?'
    info '     ./resolve-ovpn-remote.sh --check-cloudflare'
    info "Switch, or put everything back:"
    info "     ./$SELF --switch <config>     ./$SELF --stop"
    printf '\n'
}

# Without this the system resolver stays whatever it was, so a poisoned answer
# reaches you through a perfectly good tunnel. Only systemd-resolved is handled
# - it is the one that can do this per interface and undo it by itself when
# the interface goes away.
apply_dns() {
    local dev=$1 servers
    [ -n "$dev" ] || return 0
    command -v resolvectl >/dev/null 2>&1 || { warn 'resolvectl not found - DNS left as it was'; return 0; }
    servers=$(log_cat | grep -oE 'dhcp-option DNS [0-9.]+' | awk '{print $3}' | sort -u | tr '\n' ' ')
    [ -n "${servers// /}" ] || { info 'the server pushed no DNS - nothing to set'; return 0; }
    # shellcheck disable=SC2086
    if $SUDO resolvectl dns "$dev" $servers 2>/dev/null && $SUDO resolvectl domain "$dev" '~.' 2>/dev/null; then
        ok "DNS for $dev set to ${servers% }"
    else
        warn 'could not set DNS through resolvectl'
    fi
}


#----------------------------------------------------------------------- dns

# A tunnel does not fix your resolver. If DNS still leaves over the physical
# interface, a poisoned answer reaches you through a perfectly good tunnel -
# and everything looks fine except that half the web resolves to a machine on
# your own LAN.
dns_check() {
    local dev r rdev servers host sys doh forged=0 checked=0

    head_ 'DNS'

    dev=$(tunnel_dev)
    servers=$( { resolvectl status 2>/dev/null | sed -n 's/.*DNS Servers*:[[:space:]]*//p'
                 sed -n 's/^nameserver[[:space:]]\+//p' /etc/resolv.conf 2>/dev/null; } \
               | tr ' ' '\n' | grep -E '^[0-9.]+$' | sort -u)

    if [ -z "$servers" ]; then
        warn 'could not work out which resolver this machine uses'
    else
        for r in $servers; do
            rdev=$(ip route get "$r" 2>/dev/null | sed -n 's/.*[[:space:]]dev[[:space:]]\+\([^[:space:]]*\).*/\1/p' | head -n1)
            if [ -n "$dev" ] && [ "$rdev" = "$dev" ]; then
                ok "$r  goes through the tunnel"
            elif is_reserved_ip "$r" 2>/dev/null; then
                info "$r  is on your own network, reached over ${rdev:-?}"
            else
                warn "$r  leaves over ${rdev:-?} - outside the tunnel"
            fi
        done
    fi

    # The question that actually matters: is what your line answers still a
    # lie? We know the truth for the hostnames we pinned, so ask both.
    host=$(awk -F'\t' '$8 == "ok" && $5 != "" { print $1; exit }' "$(pins_tsv)" 2>/dev/null)
    if [ -z "$host" ]; then
        info 'nothing pinned from a hostname yet, so there is nothing to compare'
        printf '\n'
        return 0
    fi

    sys=$(getent ahostsv4 "$host" 2>/dev/null | awk '{ print $1 }' | sort -u | tr '\n' ' ')
    doh=$(resolve_doh "$host" '' | tr '\n' ' ')
    [ -n "$sys" ] || { warn "the system resolver would not answer for $host at all"; printf '\n'; return 0; }

    for r in $sys; do
        checked=$((checked + 1))
        is_reserved_ip "$r" && forged=1
    done

    printf '\n'
    info "asking about $host"
    info "  your resolver: ${sys% }"
    info "  over DoH:      ${doh:-no answer}"

    if [ "$forged" -eq 1 ]; then
        bad 'your resolver is still handing back a private address - it is lying,'
        info 'and those answers are not coming through the tunnel. Use --set-dns,'
        info 'or point the machine at 1.1.1.1 yourself.'
    elif [ -n "$doh" ] && [ -z "$(comm -12 <(tr ' ' '\n' <<<"$sys" | sort -u) <(tr ' ' '\n' <<<"$doh" | sort -u) | grep .)" ]; then
        warn 'the two disagree completely. That can be honest - big providers'
        info 'answer differently per region - but on a censored line it is also'
        info 'what interception looks like. Worth a second look.'
    else
        ok 'the answers agree, so nothing is rewriting your DNS'
    fi
    printf '\n'
}


#--------------------------------------------------------------- kill switch

# Everything that is not the tunnel, blocked, in a table of our own so that
# tearing it down cannot take anyone else's rules with it. IPv6 goes too:
# `inet` with a drop policy covers both families, which is the point - a v6
# route around a v4 tunnel is the classic leak.
killswitch_on() {
    local dev=$1 server=$2 extra=$3
    command -v nft >/dev/null 2>&1 || {
        warn 'nftables (nft) is not installed - no kill switch'
        info 'apt install nftables / dnf install nftables, or leave it off.'
        return 1
    }
    [ -n "$dev" ] || { warn 'no tunnel device to build a kill switch around'; return 1; }

    local allow=''
    [ -n "$server" ] && allow="$allow        ip daddr $server accept"$'\n'
    local u
    for u in $extra; do allow="$allow        ip daddr $u accept"$'\n'; done

    if $SUDO nft -f - <<EOF 2>/dev/null
table inet ovpn_pin {
    chain out {
        type filter hook output priority 0; policy drop;
        oif lo accept
        oifname "$dev" accept
$allow        ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4 } accept
        ip protocol icmp accept
    }
}
EOF
    then
        printf '%s\n' "$dev" > "$STATE/killswitch"
        ok 'kill switch on - if the tunnel drops, traffic stops rather than leaking'
        info 'To take it off by hand at any time:'
        info '     sudo nft delete table inet ovpn_pin'
        return 0
    fi
    warn 'could not install the kill switch - rules refused'
    return 1
}

killswitch_off() {
    local quiet=${1:-0}
    command -v nft >/dev/null 2>&1 || {
        [ "$quiet" -eq 0 ] && info 'nftables is not installed here, so there is no kill switch of ours to remove'
        return 0
    }
    if $SUDO nft list table inet ovpn_pin >/dev/null 2>&1; then
        $SUDO nft delete table inet ovpn_pin 2>/dev/null && \
            { [ "$quiet" -eq 1 ] || ok 'kill switch removed'; }
    elif [ "$quiet" -eq 0 ]; then
        info 'no kill switch of ours was in place'
    fi
    rm -f -- "$STATE/killswitch" 2>/dev/null
    return 0
}


#-------------------------------------------------------------------- service

install_service() {
    local cfg=$1 unit=/etc/systemd/system/ovpn-pin.service body

    body=$(cat <<EOF
[Unit]
Description=ovpn-pin: $cfg
Documentation=file://$ROOT/README.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$ROOT/$SELF $cfg --supervise --fallback stop
ExecStop=$ROOT/$SELF --stop
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
)

    head_ 'systemd unit'
    say ''
    printf '%s\n' "$body" | while IFS= read -r l; do info "$l"; done
    say ''

    if [ "$DRY_RUN" -eq 1 ]; then
        info "--dry-run: not written. It would go to $unit"
        printf '\n'
        return 0
    fi

    printf '%s\n' "$body" | $SUDO tee "$unit" >/dev/null || die "cannot write $unit"
    $SUDO systemctl daemon-reload 2>/dev/null
    ok "written to $unit"
    printf '\n'
    info 'Start it now, and at every boot:'
    info '     sudo systemctl enable --now ovpn-pin'
    info 'It runs as root, which has no desktop session - so the system proxy'
    info 'is not switched off by it. Set OVPN_PROXY_OFF_CMD in .env if that'
    info 'matters for an unattended machine.'
    printf '\n'
}

# What the unit runs: connect, then hold the foreground until the tunnel dies,
# so systemd can see it die and start us again.
supervise() {
    info 'supervising - this stays in the foreground until the tunnel goes'
    trap 'printf "\n"; do_stop 1; exit 0' INT TERM
    local pid
    while pid=$(running_pid); do sleep 5; done
    printf '\n'
    bad 'the tunnel is gone'
    do_stop 1
    exit 1
}


#---------------------------------------------------------------- keeping what works

# Tenths of a second since a `date +%s%3N` stamp, and how to print them.
# Tenths rather than seconds because half the configs come up in two or three
# and a whole-second figure would call most of them the same.
elapsed_tenths() { printf '%s' $(( ( $(date '+%s%3N') - $1 + 50 ) / 100 )); }
fmt_tenths()     { printf '%02d.%01d' $(( $1 / 10 )) $(( $1 % 10 )); }

# A config that came up is worth keeping hold of, and how long it took to come
# up is worth keeping with it: when a tunnel drops at an awkward moment the
# question is not which exit is cleanest, it is which one is quickest to have
# working again. Sorted by name, that folder answers it.
#
# Zero-padded, because a plain alphabetical sort - which is what every file
# manager and every shell does by default - puts "10s" before "4s" otherwise,
# and a list that lies about its own order is worse than no list.
#
# With a tag, the same thing lands in success/landlord as well, labelled with
# who the exit belongs to and which country it came out in.
save_successful() {
    local src=$1 tenths=$2 dir=$3 tag=${4:-} base name dest f
    mkdir -p -- "$dir" || return 1
    base=$(base_config_name "$(basename -- "$src")")
    if [ -n "$tag" ]; then name="$(fmt_tenths "$tenths")s-${tag}-${base}"
    else                   name="$(fmt_tenths "$tenths")s-${base}"
    fi
    dest=$dir/$name

    # Copy before deleting, and never delete what is being copied: when the
    # folder being swept is this one, the source file is the previous run's
    # entry for this very config.
    #
    # A copy that does not happen is reported by the exit status and nothing
    # else. This function's stdout is the name the caller captures, so prose
    # in there would come back as part of a filename - and saying nothing at
    # all is how an empty landlord/ folder went unnoticed.
    [ "$src" -ef "$dest" ] || cp -f -- "$src" "$dest" || return 1

    # Swept again, a config replaces its own old entry rather than sitting
    # beside it under a different time - or, in the landlord folder, under a
    # different landlord, which does change when a provider moves a location
    # onto someone else's racks.
    for f in "$dir"/*"-$base"; do
        [ -e "$f" ] || continue
        [ "$f" = "$dest" ] || rm -f -- "$f"
    done
    printf '%s' "$name"
}

# A config filename split into the two things worth grouping by, without a
# subshell for either. BASE_KEY is the name with any "04.5s-" taken off;
# LOC_KEY is that with the address taken off too, which is what several files
# of the same location share.
#
# Done in the shell rather than through base_config_name and sed because this
# runs once per config: at fifteen hundred of them that is three thousand
# processes, and the wait is long enough to look like a hang.
LOC_KEY=''
BASE_KEY=''
split_config_name() {
    local n=${1##*/}
    [[ $n =~ ^[0-9]{1,3}(\.[0-9]+)?s-(.*)$ ]] && n=${BASH_REMATCH[2]}
    BASE_KEY=$n
    [[ $n =~ ^(.*)_[0-9.]+\.ovpn$ ]] && n=${BASH_REMATCH[1]}
    LOC_KEY=$n
}

# What each config's handshake took the last time it connected, in tenths,
# read back out of the names in the success folder. Nothing else has it:
# exits.tsv keeps the verdict and the date but never the timing, which only
# ever gets written into the filename.
#
# A config that is not in there is unknown rather than slow, and the callers
# have to keep those two apart.
declare -A KNOWN_TENTHS=()
load_known_times() {
    local f n
    KNOWN_TENTHS=()
    [ -d "$SUCCESS_DIR" ] || return 0
    for f in "$SUCCESS_DIR"/*.ovpn; do
        [ -e "$f" ] || continue
        n=${f##*/}
        [[ $n =~ ^([0-9]{1,3})\.([0-9])s-(.+)$ ]] || continue
        KNOWN_TENTHS[${BASH_REMATCH[3]}]=$(( 10#${BASH_REMATCH[1]} * 10 + 10#${BASH_REMATCH[2]} ))
    done
}

# Of the files in this folder sharing a tag, keep the quickest and drop the
# rest. The names carry zero-padded seconds at the front - 04.5s-... - which
# was done so that a file manager sorts them honestly; it means the plain
# alphabetical order here is already fastest-first, and no arithmetic and no
# parsing of the name is needed to find the winner.
keep_fastest() {
    local dir=$1 tag=$2 f first=1
    [ -d "$dir" ] || return 0
    while IFS= read -r f; do
        [ -e "$f" ] || continue
        if [ "$first" -eq 1 ]; then first=0; continue; fi
        rm -f -- "$f"
    done < <(find "$dir" -maxdepth 1 -type f -name "*s-$tag-*" | sort)
}

# Take a config out of a folder that says something about it which has stopped
# being true. A folder named after a site has to mean what it says, or it is
# worse than not being there. Returns 0 if anything actually went.
drop_from() {
    local dir=$1 name=$2 base f gone=1
    [ -d "$dir" ] || return 1
    base=$(base_config_name "$name")
    for f in "$dir"/*"-$base"; do
        [ -e "$f" ] || continue
        rm -f -- "$f" && gone=0
    done
    return $gone
}

# Re-testing the success folder is a re-test of things that used to work, so
# one that no longer connects should not keep sitting in a folder that claims
# otherwise. Only ever the copies - the original in the pinned folder is left
# alone, and a later sweep can put it back.
drop_successful() {
    local name=$1 why=$2 base d f gone=0
    [ "$RETESTING" -eq 1 ] || return 0
    base=$(base_config_name "$name")
    # Every folder that says this config works, the per-site ones included: a
    # config that no longer connects cannot be serving anybody's site either,
    # and a folder left claiming otherwise is worse than one merely out of
    # date - you would go to it precisely when you are in a hurry.
    for d in "$SUCCESS_DIR" "$SUCCESS_DIR/landlord" "$SUCCESS_DIR/landlord/fastest" \
             "$SITETEST_DIR"/*/; do
        [ -d "$d" ] || continue
        for f in "${d%/}"/*"-$base"; do
            [ -e "$f" ] || continue
            rm -f -- "$f" && gone=1
        done
    done
    [ "$gone" -eq 1 ] && info "dropped from the folders that said it works - it $why"
    return 0
}


#-------------------------------------------------------------- by landlord

# The hosting company behind each config's address, looked up once for the
# whole list. Fills LORD_OF, keyed by index.
declare -A LORD_OF=()
load_landlords() {
    local idxs=("$@") i ips=()
    for i in "${idxs[@]}"; do ips+=("${CFG_IP[$i]}"); done
    info "looking up who ${#ips[@]} addresses are rented from"
    OWNER_PROXY=$PROXY lookup_owners "${ips[@]}"
    LORD_OF=()
    for i in "${idxs[@]}"; do
        LORD_OF[$i]=$(owner_of "${CFG_IP[$i]}" || printf '')
    done
}

# "1,3" / "1-4" / "2,5-7" -> the numbers meant, out of range dropped rather
# than silently taken for something else.
select_indexes() {
    local spec=$1 max=$2 part a b n out=()
    for part in ${spec//,/ }; do
        if [[ $part =~ ^([0-9]+)-([0-9]+)$ ]]; then
            a=${BASH_REMATCH[1]}; b=${BASH_REMATCH[2]}
            [ "$a" -le "$b" ] || { n=$a; a=$b; b=$n; }
            for ((n = a; n <= b; n++)); do [ "$n" -ge 1 ] && [ "$n" -le "$max" ] && out+=("$n"); done
        elif [[ $part =~ ^[0-9]+$ ]]; then
            [ "$part" -ge 1 ] && [ "$part" -le "$max" ] && out+=("$part")
        fi
    done
    printf '%s\n' "${out[@]}" | awk 'NF' | sort -un
}

# Narrow the sweep to whole hosting companies, either from --landlord or by
# showing the list and asking.
#
# The survivors come back in LORD_KEEP rather than on stdout. This function
# talks to a person - a numbered list, a question - and a function that prints
# both prose and data has to have one of them captured by the caller, which
# means capturing the other by accident. It did, once: the menu text ended up
# in the array of indexes and the sweep set off to connect a config called
# "Landlords".
declare -a LORD_KEEP=()
filter_by_landlord() {
    local idxs=("$@") i lord names=() counts=() keep=() want=$LANDLORD w hit
    LORD_KEEP=()
    head_ 'Landlords'
    load_landlords "${idxs[@]}"

    if [ "$PICK_LANDLORD" -eq 1 ]; then
        local -A tally=() ccs=()
        for i in "${idxs[@]}"; do
            lord=${LORD_OF[$i]}
            [ -n "$lord" ] || continue
            tally[$lord]=$(( ${tally[$lord]:-0} + 1 ))
            local cc; cc=$(owner_country "${CFG_IP[$i]}")
            case " ${ccs[$lord]:-} " in *" $cc "*) ;; *) ccs[$lord]="${ccs[$lord]:-}${cc:+ $cc}" ;; esac
        done
        if [ ${#tally[@]} -eq 0 ]; then
            warn 'nothing could be looked up, so there is nothing to choose between.'
            printf '%s\n' "${idxs[@]}"
            return 0
        fi

        head_ 'Who these are rented from'
        local sorted=() n=0
        while IFS=$'\t' read -r c lord; do
            sorted+=("$lord")
            n=$((n + 1))
            local where cnt
            cnt=$(wc -w <<<"${ccs[$lord]}")
            if [ "$cnt" -le 3 ]; then where=$(sed 's/^ //; s/ /, /g' <<<"${ccs[$lord]}")
            else where="$cnt countries"
            fi
            printf '   %s%2d%s  %-30s %4d %s   %s\n' "$C_CYAN" "$n" "$C_OFF" "$lord" "$c" \
                "$([ "$c" -eq 1 ] && printf 'config ' || printf 'configs')" "$where"
        done < <(for lord in "${!tally[@]}"; do printf '%s\t%s\n' "${tally[$lord]}" "$lord"; done | sort -rn)

        printf '\n'
        info 'Addresses under one company are close to one address, as far as being'
        info 'blocked goes. Picking a few of these and sweeping those is usually a'
        info 'better hour than sweeping everything.'
        printf '\n'
        # Plain stdin, like the config menu above - reading from /dev/tty
        # instead would ignore a piped answer and sit there waiting for a
        # keyboard that a scripted run does not have.
        local spec='' picked=()
        printf '         %swhich ones? numbers, e.g. 1,3 or 1-4 (enter for all):%s ' "$C_GRAY" "$C_OFF"
        read -r spec || spec=''
        if [ -n "${spec// /}" ]; then
            while IFS= read -r n; do picked+=("${sorted[$((n - 1))]}"); done < <(select_indexes "$spec" "${#sorted[@]}")
            if [ ${#picked[@]} -eq 0 ]; then
                warn 'nothing in that answer named a landlord on the list - taking all of them.'
            else
                want=$(printf '%s,' "${picked[@]}"); want=${want%,}
                info "chosen: ${want//,/, }"
            fi
        fi
    fi

    if [ -z "$want" ]; then
        LORD_KEEP=("${idxs[@]}")
        return 0
    fi

    for i in "${idxs[@]}"; do
        lord=${LORD_OF[$i]}
        [ -n "$lord" ] || continue
        hit=0
        local IFS=,
        for w in $want; do
            [ -n "$w" ] || continue
            [[ ${lord,,} == *"${w,,}"* ]] && { hit=1; break; }
        done
        unset IFS
        [ "$hit" -eq 1 ] && keep+=("$i")
    done
    # Said plainly when it is not the final number. This line lands right
    # after you pick, it is the biggest figure on the screen, and read on its
    # own it looks like the whole lot is about to be connected.
    if [ "$ONE_PER" -eq 1 ] || [ "$ONE_PER_LANDLORD" -eq 1 ] || [ "$ONE_PER_LANDLORD_LOC" -eq 1 ]; then
        info "${#keep[@]} config(s) are rented from those, before narrowing further"
    else
        info "${#keep[@]} config(s) are rented from those"
    fi
    LORD_KEEP=("${keep[@]}")
}


#------------------------------------------------------------------- sweeping

# Connect each pinned config in turn and ask Cloudflare what it makes of the
# exit. There is no way to know this without connecting: nothing measurable
# from your own line says how an exit you are not using will be treated.
do_sweep() {
    local filter=${1:-} i idxs=() sites=() h
    while IFS= read -r h; do [ -n "$h" ] && sites+=("$h"); done < <(site_hosts "${SITES[@]:-}")

    for i in "${!CFG_FILE[@]}"; do
        if [ -n "$filter" ]; then
            local n=${CFG_NAME[$i],,}
            [[ $n == *"${filter,,}"* ]] || continue
        fi
        idxs+=("$i")
    done
    [ ${#idxs[@]} -gt 0 ] || die "nothing to sweep${filter:+ matching '$filter'}"

    # By landlord, before anything else narrows it: choosing two hosting
    # companies out of twenty is a bigger cut than anything below, and the
    # choice is only meaningful while the whole list is still on the table.
    if [ -n "$LANDLORD" ] || [ "$PICK_LANDLORD" -eq 1 ]; then
        filter_by_landlord "${idxs[@]}"
        idxs=("${LORD_KEEP[@]:-}")
        [ -n "${idxs[0]:-}" ] || die 'no config is rented from any of those'
    fi

    # Narrowing to one address per group. Three shapes of group, one piece of
    # machinery: only the key changes.
    #
    #   --one-per                    the location            ~141 tests
    #   --one-per-landlord-location  the company, per place  ~150 tests
    #   --one-per-landlord           the company             ~21 tests
    #
    # The middle one is the one to reach for once a sweep has told you a
    # company is worth having: a location is only ever rented from one
    # company, but a company is spread over dozens of locations and they do
    # not share a fate - HostRoyale being fine in Paris says nothing about
    # HostRoyale in Lisbon. Nine locations, nine tests, whatever the
    # forty-nine files underneath them say.
    #
    # And out of each group it takes the one that was quickest last time
    # rather than whichever sorts first, since the names in success/ have been
    # carrying that number all along.
    local group_by=''
    if   [ "$ONE_PER_LANDLORD" -eq 1 ];     then group_by=lord
    elif [ "$ONE_PER_LANDLORD_LOC" -eq 1 ]; then group_by=lordloc
    elif [ "$ONE_PER" -eq 1 ];              then group_by=loc
    fi

    if [ -n "$group_by" ]; then
        if [ $((ONE_PER + ONE_PER_LANDLORD + ONE_PER_LANDLORD_LOC)) -gt 1 ]; then
            info "more than one --one-per… asked for; using --$(
                case $group_by in
                    lord)    printf 'one-per-landlord' ;;
                    lordloc) printf 'one-per-landlord-location' ;;
                    *)       printf 'one-per' ;;
                esac)"
        fi

        # --landlord/--pick-landlord already paid for the lookup. On their own
        # the company modes have to ask for it.
        if [ "$group_by" != loc ] && [ ${#LORD_OF[@]} -eq 0 ]; then
            head_ 'Landlords'
            load_landlords "${idxs[@]}"
        fi
        load_known_times

        local lord key t kept=() order=() untraced=0 known=0
        declare -A pick_idx=() pick_t=()
        for i in "${idxs[@]}"; do
            split_config_name "${CFG_NAME[$i]}"
            if [ "$group_by" = loc ]; then
                key=$LOC_KEY
            else
                lord=${LORD_OF[$i]:-}
                [ -n "$lord" ] || { untraced=$((untraced + 1)); continue; }
                if [ "$group_by" = lord ]; then key=$lord
                else                            key="$lord|$LOC_KEY"
                fi
            fi

            # Unknown sorts last. A config that has never connected should not
            # displace one measured at four seconds just because there is
            # nothing on record about it.
            t=${KNOWN_TENTHS[$BASE_KEY]:-99999}
            if [ -z "${pick_idx[$key]+set}" ]; then
                order+=("$key"); pick_idx[$key]=$i; pick_t[$key]=$t
            elif [ "$t" -lt "${pick_t[$key]}" ]; then
                pick_idx[$key]=$i; pick_t[$key]=$t
            fi
        done

        for key in "${order[@]}"; do
            kept+=("${pick_idx[$key]}")
            [ "${pick_t[$key]}" -lt 99999 ] && known=$((known + 1))
        done
        [ ${#kept[@]} -gt 0 ] || die 'not one of these addresses could be traced to a hosting company, so there is nothing to take one of. Sweep with --one-per instead.'
        [ "$untraced" -gt 0 ] && warn "$untraced address(es) could not be traced to a company - left out"
        idxs=("${kept[@]}")

        local n=${#idxs[@]} plural=s
        [ "$n" -eq 1 ] && plural=''
        case $group_by in
            lord)    info "$n compan$([ "$n" -eq 1 ] && printf y || printf ies), one address each" ;;
            lordloc) info "$n company-and-location pair$plural, one address each" ;;
            loc)     info "$n location$plural, one address each" ;;
        esac
        [ "$known" -gt 0 ] &&
            info "$known of them chosen as the quickest a previous sweep recorded"
    fi

    if [ "$FIRST" -gt 0 ] && [ ${#idxs[@]} -gt "$FIRST" ]; then
        idxs=("${idxs[@]:0:$FIRST}")
    fi

    head_ "Sweep (${#idxs[@]} configs${sites[0]:+, also testing ${sites[*]}})"
    info 'Each one gets connected, judged, and dropped again. Nothing here'
    info 'waits on a clock - every step moves on the moment it is done - so'
    info "reckon on half a minute each, ${#idxs[@]} to go."
    [ "$RETESTING" -eq 1 ] && info 'Re-testing what worked: anything that has stopped connecting is dropped from it.'

    if running_pid >/dev/null; then
        info 'bringing down what is connected first'
        do_stop 1
    fi

    local results=() verdict mode cf_exit cf_good cf_bad cf_detail cf_sites
    local lord_note lord_tag fast pair site_host site_dir st
    local base t0 took kept kept_path also exit_ip owner loc tag detail
    for i in "${idxs[@]}"; do
        printf '\n'
        # Everything downstream is keyed on the name without the time in
        # front, so a config gets one row whether it was swept from the pinned
        # folder or from the success one.
        base=$(base_config_name "${CFG_NAME[$i]}")
        info "-> $base  ${CFG_IP[$i]}:${CFG_PORT[$i]}"

        mode=direct
        if [ "$VIA" = proxy ]; then
            mode=proxy
        elif ! probe_direct "$i"; then
            if [ "$VIA" = auto ] && [ "$FALLBACK" = proxy ]; then
                mode=proxy
            else
                bad 'not reachable directly - skipped'
                drop_successful "${CFG_NAME[$i]}" 'does not answer any more'
                tsv_put "$(exits_tsv)" "$base" '' \
                    "$(printf '%s\t%s\t%s\t%s\t%s' "$base" "${CFG_IP[$i]}" 'unreachable' "$(today)" 'address does not answer')"
                results+=("$(printf '%s\t%s\t%s\t%s\t%s' 'unreachable' "$base" '-' 'address does not answer' '')")
                continue
            fi
        fi
        [ "$mode" = proxy ] && { resolve_proxy || { bad 'no proxy to dial through - skipped'; continue; }; }

        t0=$(date '+%s%3N')
        if ! start_tunnel "$i" "$mode"; then
            results+=("$(printf '%s\t%s\t%s\t%s\t%s' 'noconnect' "$base" '-' 'openvpn would not start' '')")
            continue
        fi
        if ! wait_for_up >/dev/null 2>&1; then
            bad 'did not come up'
            do_stop 1
            drop_successful "${CFG_NAME[$i]}" 'does not connect any more'
            tsv_put "$(exits_tsv)" "$base" '' \
                "$(printf '%s\t%s\t%s\t%s\t%s' "$base" "${CFG_IP[$i]}" 'noconnect' "$(today)" 'no handshake')"
            results+=("$(printf '%s\t%s\t%s\t%s\t%s' 'noconnect' "$base" '-' 'no handshake' '')")
            continue
        fi

        # Timed to here rather than to the end of the measuring: this is what
        # you would wait through if you connected it by hand.
        took=$(elapsed_tenths "$t0")
        # kept_path, not CFG_FILE[$i], is the copy of this config that is
        # certain to be on disk from here on. Sweeping the success folder
        # itself, the call below replaces this config's previous entry - and
        # that entry is the very file CFG_FILE[$i] names, so afterwards the
        # old path points at a deletion. Same bytes, different name.
        if kept=$(save_successful "${CFG_FILE[$i]}" "$took" "$SUCCESS_DIR" ''); then
            ok "up in $(fmt_tenths "$took")s - kept as $kept"
            kept_path=$SUCCESS_DIR/$kept
        else
            warn "up in $(fmt_tenths "$took")s, but it could not be copied into $(basename -- "$SUCCESS_DIR")/"
            kept_path=${CFG_FILE[$i]}
        fi

        IFS=$'\t' read -r verdict cf_exit cf_good cf_bad cf_detail cf_sites \
            < <(cf_exit_verdict "${sites[@]:-}")
        case $verdict in
            clean)  ok   "clean     exit ${cf_exit:-?}  ($cf_good served)" ;;
            partly) warn "partly    exit ${cf_exit:-?}  ($cf_good served, $cf_bad refused: $cf_detail)" ;;
            dirty)  bad  "flagged   exit ${cf_exit:-?}  (everything refused)" ;;
            *)      warn "$verdict  ${cf_detail:-}" ;;
        esac

        # Who the exit is rented from. Done here rather than up front because
        # the exit is rarely the address you dialled - providers NAT it - so
        # this is the only moment the real one is known. The country comes
        # from Cloudflare rather than the file's name: de-fra is where they
        # say it is, DE is where it answered from.
        owner=''
        exit_ip=${cf_exit%% *}
        loc=${cf_exit#* }; [ "$loc" = "$cf_exit" ] && loc=''
        if [ "$NO_OWNER" -eq 0 ] && [ -n "$exit_ip" ]; then
            OWNER_PROXY='' lookup_owners "$exit_ip"
            if owner=$(owner_of "$exit_ip"); then
                info "rented from $owner${loc:+ in $loc}"

                # Two views of the same company, and both are kept to one file
                # apiece rather than to all of them. landlord/ answers "what is
                # my quickest M247 in Germany" - the question when a whole
                # company turns out to be blocked and you want the same country
                # from somebody else's racks. landlord/fastest/ answers "what is
                # my quickest M247 anywhere", which is the one to reach for when
                # you do not care where it lands.
                lord_note=''
                tag="$(name_tag "$owner")-$(name_tag "${loc:-xx}" 6)"
                if also=$(save_successful "$kept_path" "$took" "$SUCCESS_DIR/landlord" "$tag"); then
                    keep_fastest "$SUCCESS_DIR/landlord" "$tag"
                    [ -e "$SUCCESS_DIR/landlord/$also" ] && lord_note="landlord/$also"
                fi
                lord_tag=$(name_tag "$owner")
                if fast=$(save_successful "$kept_path" "$took" "$SUCCESS_DIR/landlord/fastest" "$lord_tag"); then
                    keep_fastest "$SUCCESS_DIR/landlord/fastest" "$lord_tag"
                    [ -e "$SUCCESS_DIR/landlord/fastest/$fast" ] &&
                        lord_note="${lord_note:+$lord_note, }landlord/fastest/$fast"
                fi
                if [ -n "$lord_note" ]; then info "also kept as $lord_note"
                else info "a quicker $owner is already kept - not this one"
                fi
            else
                owner=''
            fi
        fi

        # A folder per site you named, holding the configs that actually served
        # it. "Which of these gets me into chatgpt.com" is a different question
        # from "which of these is clean", and reading it out of a detail string
        # after the fact is not an answer.
        if [ -n "${cf_sites:-}" ]; then
            for pair in $cf_sites; do
                site_host=${pair%%=*}
                site_dir=$SITETEST_DIR/$(name_tag "$site_host")
                if [ "${pair##*=}" = ok ]; then
                    # Every config that serves it, not just the quickest: this
                    # folder is a list of what works, and one entry would make
                    # it a single point of failure dressed as a survey.
                    if st=$(save_successful "$kept_path" "$took" "$site_dir" ''); then
                        info "serves $site_host - kept in $(basename -- "$SITETEST_DIR")/$(name_tag "$site_host")/$st"
                    else
                        warn "serves $site_host but could not be copied into $site_dir"
                    fi
                elif drop_from "$site_dir" "$kept"; then
                    info "no longer serves $site_host - dropped from that folder"
                fi
            done
        fi

        detail=$cf_detail
        [ -n "$owner" ] && detail="$cf_detail [$owner]"
        tsv_put "$(exits_tsv)" "$base" '' \
            "$(printf '%s\t%s\t%s\t%s\t%s' "$base" "$exit_ip" "$verdict" "$(today)" "$detail")"
        results+=("$(printf '%s\t%s\t%s\t%s\t%s' "$verdict" "$base" "${cf_exit:-?}" "$cf_detail" "$took")")

        do_stop 1
    done

    head_ 'Sweep results'
    local line v name exit_ detail secs best=''
    # clean first, then partly, then the rest: the order you would try them in.
    while IFS=$'\t' read -r v name exit_ detail secs; do
        [ -n "$name" ] || continue
        case $v in
            clean)  ok   "$(printf '%-38s %-22s %s' "$name" "$exit_" 'clean')"; [ -n "$best" ] || best=$name ;;
            partly) warn "$(printf '%-38s %-22s %s' "$name" "$exit_" "partly - $detail")"; [ -n "$best" ] || best=$name ;;
            dirty)  bad  "$(printf '%-38s %-22s %s' "$name" "$exit_" 'flagged - Cloudflare refuses it')" ;;
            *)      info "$(printf '%-38s %-22s %s' "$name" "$exit_" "$v - $detail")" ;;
        esac
    done < <(printf '%s\n' "${results[@]}" | sort -t$'\t' -k1,1)

    # What connected, quickest first. The table above answers "which exit is
    # clean"; this one answers "which comes up fastest", and they are different
    # questions with different winners - the second one being what you want at
    # the moment a tunnel drops.
    local up=()
    while IFS= read -r line; do [ -n "$line" ] && up+=("$line"); done < <(
        printf '%s\n' "${results[@]}" | awk -F'\t' '$5 != "" { print $5 "\t" $2 "\t" $1 }' | sort -n)
    if [ ${#up[@]} -gt 0 ]; then
        head_ 'Quickest to connect'
        local shown=0
        for line in "${up[@]}"; do
            IFS=$'\t' read -r secs name v <<<"$line"
            [ "$shown" -lt 10 ] && info "$(printf '%6ss  %-46s %s' "$(fmt_tenths "$secs")" "$name" "$v")"
            shown=$((shown + 1))
        done
        [ "$shown" -gt 10 ] && info "... and $((shown - 10)) more"
        printf '\n'
        info "All $shown that connected are in $SUCCESS_DIR, named by how long they"
        info 'took - sort that folder by name and the top of it is what to reach'
        info 'for when a tunnel drops.'
        local lord_n
        lord_n=$(find "$SUCCESS_DIR/landlord" -maxdepth 1 -type f -name '*.ovpn' 2>/dev/null | wc -l)
        if [ "$lord_n" -gt 0 ]; then
            info "The $lord_n whose exit could be traced are also in $SUCCESS_DIR/landlord,"
            info 'with the hosting company and the country in the name.'
        fi
    fi

    printf '\n'
    if [ -z "$best" ]; then
        bad 'not one of these exits is served by Cloudflare.'
        info 'Re-pin for fresh addresses (./resolve-ovpn-remote.sh --sync) or get'
        info 'configs for other servers from your provider.'
        printf '\n'
        return 1
    fi

    if [ "$PICK" -eq 1 ]; then
        info "connecting the best of them: $best"
        printf '\n'
        SELECTOR=$best
        return 2
    fi

    info "nothing is connected now. The best of them was:"
    info "     ./$SELF $best"
    printf '\n'
    return 0
}


#------------------------------------------------------------- stop / status

do_stop() {
    local pid mode='' quiet=${1:-0}
    pid=$(running_pid) || {
        [ "$quiet" -eq 1 ] && return 0
        head_ 'Nothing to stop'
        info 'no tunnel of ours is running.'
        proxy_on
        printf '\n'
        return 0
    }
    [ -f "$MODEFILE" ] && mode=$(cat "$MODEFILE")

    [ "$quiet" -eq 1 ] || head_ 'Stopping'

    # Before anything else: a kill switch outlives the tunnel it was built
    # around, and one left behind looks exactly like the network being broken.
    [ -f "$STATE/killswitch" ] && killswitch_off "$quiet"

    # The proxy goes back first: the next connect may well need it, and in
    # proxied mode the tunnel is about to stop needing it anyway.
    proxy_on

    $SUDO kill "$pid" 2>/dev/null
    local waited=0
    while [ "$waited" -lt 15 ] && pid_alive "$pid"; do sleep 1; waited=$((waited + 1)); done

    if pid_alive "$pid"; then
        # Never SIGKILL by choice: a killed openvpn leaves its routes and DNS
        # behind, and the next connect then fails for reasons that look like
        # anything but this.
        warn "openvpn ($pid) did not exit after 15s. Not killing it harder -"
        info 'that would leave its routes behind. Try again in a moment.'
        return 1
    fi

    unpin_upstream_routes
    $SUDO rm -f -- "$PIDFILE"
    rm -f -- "$CURFILE" "$MODEFILE" 2>/dev/null

    if [ "$quiet" -eq 0 ]; then
        ok 'tunnel down, routes removed by openvpn on its way out'
        local dev
        dev=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*[[:space:]]dev[[:space:]]\+\([^[:space:]]*\).*/\1/p' | head -n1)
        [ -n "$dev" ] && info "traffic leaves over $dev again"
        printf '\n'
    fi
    return 0
}

do_status() {
    local pid cur mode dev eg gs
    head_ 'Status'

    if pid=$(running_pid); then
        cur=$(cat "$CURFILE" 2>/dev/null)
        mode=$(cat "$MODEFILE" 2>/dev/null)
        ok "up: $(basename -- "${cur:-unknown}")  (pid $pid, ${mode:-?})"
        dev=$(tunnel_dev); [ -n "$dev" ] && info "device: $dev"
        [ "$mode" = proxy ] && info "carried by $PROXY - closing it drops the tunnel"
    else
        info 'no tunnel of ours is up.'
    fi

    if command -v ip >/dev/null 2>&1; then
        dev=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*[[:space:]]dev[[:space:]]\+\([^[:space:]]*\).*/\1/p' | head -n1)
        [ -n "$dev" ] && info "traffic leaves over $dev"
    fi
    if command -v gsettings >/dev/null 2>&1; then
        gs=$(gsettings get org.gnome.system.proxy mode 2>/dev/null | tr -d "'")
        [ -n "$gs" ] && info "system proxy: $gs"
    fi
    eg=$(egress_line) && info "Cloudflare sees you as $eg"
    printf '\n'
}


#------------------------------------------------------------------ arguments

SPLIT=()
for a in "$@"; do
    case $a in
        --*=*) SPLIT+=("${a%%=*}" "${a#*=}") ;;
        *)     SPLIT+=("$a") ;;
    esac
done
set -- ${SPLIT[@]+"${SPLIT[@]}"}

SELECTOR=''
ACTION=connect
CLI_FALLBACK=''
CLI_VIA=''
NO_PROXY_OFF=0
SET_DNS=''
DNS_CHECK=0
CLI_KILL=''
PICK=0
SUPERVISE=0
DRY_RUN=0
SITES=()
LANDLORD=''
PICK_LANDLORD=0
ONE_PER=0
ONE_PER_LANDLORD=0
ONE_PER_LANDLORD_LOC=0
FIRST=0
NO_OWNER=0
RETEST=0
CLI_SUCCESS=''
CLI_SITETEST=''

while [ $# -gt 0 ]; do
    case $1 in
        -h|--help)         usage; exit 0 ;;
        -V|--version)      say "$SELF $VERSION"; exit 0 ;;
        --status)          ACTION=status; shift ;;
        --stop)            ACTION=stop; shift ;;
        --sweep)           ACTION=sweep; shift ;;
        --dns-check)       ACTION=dnscheck; shift ;;
        --kill-switch-off) ACTION=unlock; shift ;;
        --install-service) ACTION=service; shift ;;
        --switch)          SELECTOR=${2:?--switch needs a config}; shift 2 ;;
        --site)            SITES+=("${2:?--site needs a value}"); shift 2 ;;
        --pick)            PICK=1; shift ;;
        --landlord)        LANDLORD=${2:?--landlord needs a name or AS number}; shift 2 ;;
        --pick-landlord)   PICK_LANDLORD=1; shift ;;
        --one-per)         ONE_PER=1; shift ;;
        --one-per-landlord) ONE_PER_LANDLORD=1; shift ;;
        --one-per-landlord-location) ONE_PER_LANDLORD_LOC=1; shift ;;
        --first)           FIRST=${2:?--first needs a number}; shift 2 ;;
        --no-owner)        NO_OWNER=1; shift ;;
        --retest)          RETEST=1; shift ;;
        --success-dir)     CLI_SUCCESS=${2:?--success-dir needs a path}; shift 2 ;;
        --sitetest-dir)    CLI_SITETEST=${2:?--sitetest-dir needs a path}; shift 2 ;;
        --fallback)        CLI_FALLBACK=${2:?--fallback needs a mode}; shift 2 ;;
        --via)             CLI_VIA=${2:?--via needs a mode}; shift 2 ;;
        --via-proxy)       CLI_VIA=proxy; shift ;;
        --next)            CLI_FALLBACK=next; shift ;;
        --no-proxy-off)    NO_PROXY_OFF=1; shift ;;
        --set-dns)         SET_DNS=1; shift ;;
        --kill-switch)     CLI_KILL=1; shift ;;
        --no-kill-switch)  CLI_KILL=0; shift ;;
        --supervise)       SUPERVISE=1; shift ;;
        --timeout)         WAIT=${2:?--timeout needs a value}; shift 2 ;;
        --dry-run)         DRY_RUN=1; shift ;;
        -*)                die "unknown option: $1  (try --help)" ;;
        *)                 [ -z "$SELECTOR" ] || die "unexpected argument: $1"
                           SELECTOR=$1; shift ;;
    esac
done

[[ $WAIT =~ ^[0-9]+$ ]] || die "--timeout must be a number, not '$WAIT'"

command -v curl >/dev/null 2>&1 || die 'curl is required and was not found.'
if [ "$ACTION" = connect ] && [ "$DRY_RUN" -eq 0 ]; then
    command -v openvpn >/dev/null 2>&1 || die 'openvpn is not installed. apt install openvpn / dnf install openvpn'
fi

if [ "$(id -u)" -eq 0 ]; then SUDO=''; else SUDO='sudo'; fi

load_env_file "$ROOT/.env"
OUT_DIR=$(env_or OVPN_OUT_DIR "$ROOT/pinned")
SUCCESS_DIR=${CLI_SUCCESS:-$(env_or OVPN_SUCCESS_DIR "$ROOT/success")}
SITETEST_DIR=${CLI_SITETEST:-$(env_or OVPN_SITETEST_DIR "$ROOT/sitetest")}
AUTH_FILE=$(env_or OVPN_AUTH_FILE "$ROOT/.ovpn-auth")
AUTH_USER=$(env_or OVPN_USER '')
AUTH_PASS=$(env_or OVPN_PASS '')
PROXY=$(env_or OVPN_PROXY '')
PROXY_OFF_CMD=$(env_or OVPN_PROXY_OFF_CMD '')
PROXY_ON_CMD=$(env_or OVPN_PROXY_ON_CMD '')
PROXY_UPSTREAM=$(env_or OVPN_PROXY_UPSTREAM '')
FALLBACK=${CLI_FALLBACK:-$(env_or OVPN_FALLBACK '')}
VIA=${CLI_VIA:-$(env_or OVPN_CONNECT_VIA 'auto')}
KILL_SWITCH=${CLI_KILL:-$(env_or OVPN_KILL_SWITCH 0)}
RESOLVER=$(env_or OVPN_RESOLVER 'cloudflare')
[ -n "$SET_DNS" ] || SET_DNS=$(env_or OVPN_SET_DNS 0)
[ "$SET_DNS" = 1 ] || SET_DNS=0
[ "$KILL_SWITCH" = 1 ] || KILL_SWITCH=0
[ ${#SITES[@]} -eq 0 ] && { s=$(env_or OVPN_SITE ''); [ -n "$s" ] && SITES=("$s"); }

case $OUT_DIR in /*) ;; *) OUT_DIR=$ROOT/${OUT_DIR#./} ;; esac
case $SUCCESS_DIR in /*) ;; *) SUCCESS_DIR=$ROOT/${SUCCESS_DIR#./} ;; esac
case $SITETEST_DIR in /*) ;; *) SITETEST_DIR=$ROOT/${SITETEST_DIR#./} ;; esac
case $AUTH_FILE in /*) ;; *) AUTH_FILE=$ROOT/${AUTH_FILE#./} ;; esac

# --retest sweeps what worked last time rather than everything. It is the same
# sweep pointed at a different folder, and it behaves in one way differently:
# a config that no longer connects is taken out of that folder, because a
# folder that says these all work should not be quietly wrong.
[ "$RETEST" -eq 1 ] && OUT_DIR=$SUCCESS_DIR
RETESTING=0
[ "${OUT_DIR%/}" = "${SUCCESS_DIR%/}" ] && RETESTING=1

case $VIA in
    auto|direct|proxy) ;;
    *) die "--via must be auto, direct or proxy - not '$VIA'" ;;
esac
[ -n "${DOH_ENDPOINTS[$RESOLVER]:-}" ] || die "OVPN_RESOLVER must be cloudflare or google, not '$RESOLVER'"

# ask on a terminal, and refuse to guess without one.
[ -n "$FALLBACK" ] || FALLBACK=$([ -t 0 ] && printf 'ask' || printf 'stop')
case $FALLBACK in
    ask|proxy|next|stop) ;;
    *) die "--fallback must be ask, proxy, next or stop - not '$FALLBACK'" ;;
esac


#----------------------------------------------------------------------- main

banner 'ovpn-connect' 'brings up a pinned config, then gets the proxy out of the way'
state_init
probe_devtcp

case $ACTION in
    status)   do_status; exit 0 ;;
    stop)     do_stop; exit 0 ;;
    unlock)   head_ 'Kill switch'; killswitch_off; printf '\n'; exit 0 ;;
    dnscheck) dns_check; exit 0 ;;
esac

# Past the read-only actions: everything below here can start a tunnel, and a
# tunnel is exactly what a stale password breaks.
refresh_auth_file

if ! list_configs; then
    if [ "$RETESTING" -eq 1 ]; then
        die "nothing in $OUT_DIR yet - nothing has been found to work so far. Sweep the pinned folder first; whatever connects lands in there."
    fi
    die "no pinned configs in $OUT_DIR. Run ./resolve-ovpn-remote.sh first."
fi

if [ "$ACTION" = service ]; then
    [ -n "$SELECTOR" ] || die "name the config the service should connect: ./$SELF --install-service de-fra"
    SEL_IDX=''
    select_config "$SELECTOR"
    install_service "${CFG_NAME[$SEL_IDX]}"
    exit 0
fi

if [ "$ACTION" = sweep ]; then
    probe_devtcp
    [ -n "$SUDO" ] && { info 'openvpn needs root:'; $SUDO -v || die 'no sudo, no sweep.'; }
    do_sweep "$SELECTOR"
    case $? in
        2) ACTION=connect ;;   # --pick: SELECTOR now names the winner
        0) exit 0 ;;
        *) exit 1 ;;
    esac
fi

SEL_IDX=''
if [ -n "$SELECTOR" ]; then select_config "$SELECTOR"; else menu; fi

# One tunnel at a time. Switching is a stop and a start, in that order, with
# the stop given time to take its routes with it.
if running_pid >/dev/null; then
    head_ 'Switching'
    info "$(basename -- "$(cat "$CURFILE" 2>/dev/null)") is up - bringing it down first"
    do_stop 1 || die 'the running tunnel would not stop, so nothing was started.'
    ok 'previous tunnel down'
fi

head_ 'Preflight'
info "${CFG_NAME[$SEL_IDX]}  ->  ${CFG_IP[$SEL_IDX]}:${CFG_PORT[$SEL_IDX]} ${CFG_PROTO[$SEL_IDX]}"

MODE=direct
if [ "$VIA" = proxy ]; then
    [[ ${CFG_PROTO[$SEL_IDX]} == udp* ]] && die 'a udp config cannot go through an HTTP proxy - OpenVPN only speaks to one over TCP. Pin a tcp config and use that.'
    resolve_proxy || die 'no local proxy found. Start v2rayN, or name it with OVPN_PROXY in .env.'
    warn "--via proxy: dialling through $PROXY without probing the direct path"
    info 'The proxy carries this tunnel, so it stays up for the whole session'
    info 'and traffic is encrypted twice. Use --via auto to let the script find'
    info 'out whether the address answers on its own.'
    MODE=proxy
elif probe_direct "$SEL_IDX"; then
    if [[ ${CFG_PROTO[$SEL_IDX]} == udp* ]]; then
        ok 'udp - not probeable, dialling it directly'
    else
        ok 'the address answers directly - the proxy is not needed for this'
    fi
else
    bad "${CFG_IP[$SEL_IDX]}:${CFG_PORT[$SEL_IDX]} does not answer directly."

    # Ask the proxy the same question. Whether it can reach the address
    # decides what the rest of this even means: a live server behind a block,
    # or an address that has stopped being a server.
    DIAG=no-answer
    if [ "$VIA" != direct ]; then
        resolve_proxy >/dev/null 2>&1 || true
        [ -n "$PROXY" ] && info "asking $PROXY whether it can reach it..."
        DIAG=$(diagnose_address "${CFG_IP[$SEL_IDX]}" "${CFG_PORT[$SEL_IDX]}" "${CFG_PROTO[$SEL_IDX]}" "$PROXY")
    fi

    case $DIAG in
        proxy-only)
            info 'the proxy reaches it, so the server is alive and it is the address'
            info 'itself your line blocks - not just its DNS. A tunnel dialled'
            info 'through the proxy is carried by it for its whole life, though,'
            info 'so it cannot be dropped once connected.' ;;
        dead)
            info 'the proxy cannot reach it either, so it is not your line - that'
            info 'server has stopped answering. Fresh addresses:'
            info '     ./resolve-ovpn-remote.sh --sync' ;;
        *)
            info 'So this one cannot be dialled without help, and a tunnel that is'
            info 'dialled through the proxy stays on the proxy for its whole life -'
            info 'there is no dropping it afterwards.' ;;
    esac

    [ "$VIA" = direct ] && { printf '\n'; show_alternatives; printf '\n'
                             die '--via direct forbids the proxy, so nothing was connected.'; }

    CHOICE=$FALLBACK
    if [ "$CHOICE" = ask ]; then
        # Offering to dial through the proxy an address the proxy cannot reach
        # either would be offering to fail slowly.
        if [ "$DIAG" = dead ]; then CHOICE=$(ask_fallback nodial)
        else CHOICE=$(ask_fallback)
        fi
    fi

    case $CHOICE in
        proxy)
            resolve_proxy || die 'no local proxy found. Start v2rayN (or name it with OVPN_PROXY in .env) and try again.'
            [[ ${CFG_PROTO[$SEL_IDX]} == udp* ]] && die 'a udp config cannot go through an HTTP proxy. Pin a tcp config and use that.'
            warn "connecting through $PROXY - it must stay up for the whole session"
            MODE=proxy ;;
        next)
            info 'looking for a pinned file that answers directly...'
            if NEXT=$(find_reachable); then
                SEL_IDX=$NEXT
                ok "using ${CFG_NAME[$SEL_IDX]}  ${CFG_IP[$SEL_IDX]}:${CFG_PORT[$SEL_IDX]}"
            else
                printf '\n'
                die 'none of the other pinned files answer directly either. Re-run ./resolve-ovpn-remote.sh --sync, or connect through the proxy with --via-proxy.'
            fi ;;
        *)
            printf '\n'
            show_alternatives
            printf '\n'
            exit 1 ;;
    esac
fi

if [ "$DRY_RUN" -eq 0 ]; then
    [ -n "$SUDO" ] && { info 'openvpn needs root:'; $SUDO -v || die 'no sudo, no tunnel.'; }
fi

head_ 'Connecting'
start_tunnel "$SEL_IDX" "$MODE" || exit 1
info "openvpn started, waiting for the handshake (up to ${WAIT}s)..."

wait_for_up
case $? in
    0)  report_up "$SEL_IDX" "$MODE"
        [ "$SUPERVISE" -eq 1 ] && supervise ;;
    1)  # A dead end. Take it back down rather than leave a daemon retrying
        # behind our back and a state file claiming a tunnel is up.
        do_stop 1
        printf '\n'
        info "log: $LOGFILE"
        printf '\n'
        exit 1 ;;
    *)  printf '\n'
        info "log: $LOGFILE"
        printf '\n'
        exit 1 ;;
esac
