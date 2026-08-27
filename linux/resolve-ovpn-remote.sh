#!/usr/bin/env bash
#
# resolve-ovpn-remote.sh - the Linux twin of Resolve-OvpnRemote.ps1.
#
# Pins the `remote` line of OpenVPN config files to a real IP address,
# resolved over DNS-over-HTTPS, and - unlike the Windows version - writes the
# credentials out beside them so the client stops asking for a username and
# password on every connect.
#
# A downloaded .ovpn file points at a hostname:
#
#     remote de-fra.prod.surfshark.com 1443 tcp
#
# On a censored line that hostname is the weak link. The resolver answers with
# a forged address - 10.10.34.35 and friends, a machine on your own LAN - and
# OpenVPN dutifully dials it and gets nowhere. It is not the tunnel failing;
# it never left the building.
#
# This script resolves each remote hostname over DoH instead, throws away any
# answer that is obviously forged, and writes the config back out with the
# address written in literally. Nothing then depends on your resolver at
# connect time.
#
# Certificate checking is unaffected. OpenVPN validates the server against the
# CA and whatever `verify-x509-name` says, none of which involves the address
# you dialled, so pinning an IP does not weaken the tunnel.
#
# Requires: bash 4+, curl, coreutils. Nothing else.

set -uo pipefail

VERSION=1.1.0
# HERE is linux/, where ovpn-lib.sh and the other scripts are. ROOT is the
# repo above it, where the reader's own things are - configs/, pinned/,
# success/, .env, the credentials - beside the platform folders rather than
# inside either one. Answering both questions with one name is how a script
# ends up writing pinned configs into linux/ and saying nothing about it.
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$HERE/.." && pwd)
SELF=$(basename -- "${BASH_SOURCE[0]}")

# Output helpers, the .env parser, the address checks and the reachability
# probe live next door, because ovpn-connect.sh needs the same ones.
# shellcheck source=ovpn-lib.sh
. "$HERE/ovpn-lib.sh" 2>/dev/null || {
    printf '\n  [fail] ovpn-lib.sh is missing from %s\n\n' "$HERE" >&2
    exit 1
}


#----------------------------------------------------------------------- help

usage() {
    cat <<EOF
  $SELF $VERSION
  pins the remote line of an OpenVPN config to a real IP, over DoH

  usage: ./$SELF [options]

    -p, --path PATH        a .ovpn file or a folder of them (default: configs/)
    -o, --out-dir DIR      where pinned copies go (default: pinned/)
        --sync             re-resolve the hostnames already pinned from and
                           rewrite the files, reporting what changed. Needs no
                           original configs - the hostnames are remembered.
        --keep-stale       with --sync, keep files for addresses that are gone
        --proxy URL        HTTP proxy for the DoH lookups, e.g.
                           http://127.0.0.1:10808. Omitted, DoH is tried
                           directly and a local proxy is looked for.
        --doh-via MODE     auto (default) | direct | proxy. 'proxy' skips the
                           direct attempt, which is 20 seconds a run on a line
                           where DoH is blocked outright.
        --resolver NAME    cloudflare (default) or google
    -n, --max-ips N        cap on files written per input (default: 4)
        --in-place         overwrite the input instead of writing copies
        --no-test          skip the reachability check on each pinned address
        --check-cloudflare judge the exit you are connected to now; pins nothing
        --site HOST[,HOST] extra hosts for --check-cloudflare

  credentials

        --env-file FILE    where the credentials live (default: .env)
        --auth-file FILE   where to write them for OpenVPN
                           (default: .ovpn-auth, mode 600)
        --add-auth         add an auth-user-pass line to configs that have
                           none, instead of only redirecting existing ones
        --no-auth          ignore credentials entirely; leave the client to ask

  A .env holding

        OVPN_USER=you@example.com
        OVPN_PASS=secret

  is written out to the auth file and every pinned config is pointed at it, so
  the client never asks again. Without one, nothing about auth is touched.

  examples

    ./$SELF
    ./$SELF --sync
    ./$SELF --path ./configs --max-ips 2
    ./$SELF --proxy http://127.0.0.1:10808 --doh-via proxy
    ./$SELF --check-cloudflare --site chatgpt.com,github.com

EOF
}


#------------------------------------------------------------------------ DoH

# A proxy to put questions to, which is not the same thing as the route the
# lookups took: DoH can be perfectly happy directly while an address is
# blocked, and that is exactly when there is something to ask. Looked for only
# once, and only when a probe has already failed - scanning ports on every run
# to answer a question nobody asked would be its own kind of rude.
PROBE_PROXY=''
PROBE_PROXY_TRIED=0
probe_proxy_url() {
    [ "$PROBE_PROXY_TRIED" -eq 1 ] && { printf '%s' "$PROBE_PROXY"; return 0; }
    PROBE_PROXY_TRIED=1
    PROBE_PROXY=${PROXY:-$ROUTE}
    if [ -z "$PROBE_PROXY" ]; then
        PROBE_PROXY=$(find_proxy "${DOH_ENDPOINTS[$RESOLVER]}") || PROBE_PROXY=''
    fi
    printf '%s' "$PROBE_PROXY"
}

# Decide once whether DoH works without help. Trying direct for every hostname
# on a line where it is blocked costs twenty seconds each time for an answer
# that was never coming.
select_doh_route() {
    local p

    if [ "$DOH_VIA" = proxy ] || { [ -n "$PROXY" ] && [ "$DOH_VIA" != direct ]; }; then
        if [ -z "$PROXY" ]; then
            info 'looking for a local proxy (--doh-via proxy)...'
            PROXY=$(find_proxy "${DOH_ENDPOINTS[$RESOLVER]}") \
                || die 'no local HTTP proxy was found. Start v2rayN (or Clash, Nekoray, sing-box, Hiddify), or name it with --proxy http://127.0.0.1:PORT.'
        fi
        info "lookups go through the proxy: $PROXY"
        ROUTE=$PROXY
        return 0
    fi

    if [ -n "$(resolve_doh example.com '')" ]; then
        ok 'DoH works directly - no proxy needed'
        ROUTE=''
        return 0
    fi

    if [ "$DOH_VIA" = direct ]; then
        die 'DoH is blocked on this line and --doh-via direct forbids the proxy. Drop that, or name a proxy with --proxy http://127.0.0.1:PORT.'
    fi

    info 'DoH did not answer directly; looking for a local proxy...'
    if p=$(find_proxy "${DOH_ENDPOINTS[$RESOLVER]}"); then
        ok "DoH will go through $p"
        ROUTE=$p
        return 0
    fi

    die 'DoH is unreachable directly and no local HTTP proxy was found. Start your proxy (v2rayN, Clash, Nekoray, sing-box, Hiddify) and run this again, or name it with --proxy http://127.0.0.1:PORT.'
}


#---------------------------------------------------------------- credentials

write_auth_file() {
    local path=$1 dir
    dir=$(dirname -- "$path")
    mkdir -p -- "$dir" || die "cannot create $dir"
    ( umask 077; printf '%s\n%s\n' "$AUTH_USER" "$AUTH_PASS" > "$path" ) \
        || die "cannot write $path"
    chmod 600 -- "$path" 2>/dev/null || true
}

# Point the config at the auth file. An existing auth-user-pass is redirected
# whatever it said; a config without one is left alone unless you ask, because
# a cert-only server that is handed a username can refuse the connection
# outright, and that failure looks nothing like its cause.
apply_auth() {
    local path=$1 i found=0 done_=0
    for i in "${!COPY[@]}"; do
        if [[ ${COPY[$i]} =~ ^([[:space:]]*)auth-user-pass([[:space:]]|$) ]]; then
            found=1
            if [ "$done_" -eq 0 ]; then
                COPY[$i]="${BASH_REMATCH[1]}auth-user-pass $path"
                done_=1
            fi
        fi
    done

    if [ "$found" -eq 0 ] && [ "$ADD_AUTH" -eq 1 ]; then
        # After the last remote line, where a reader looks for it.
        local at=$(( ${R_INDEX[-1]} + 1 ))
        COPY=("${COPY[@]:0:$at}" "auth-user-pass $path" "${COPY[@]:$at}")
        found=1
    fi

    return $(( found == 1 ? 0 : 1 ))
}


#----------------------------------------------------------------------- sync

# A pinned file is a config with its hostnames replaced by addresses and a
# header saying which was which. Put the hostnames back and you have the
# original again - which means a re-sync needs nothing but the pinned files,
# even years after the download folder was tidied away.
restore_template() {
    local pinned=$1 out=$2 line ip host mapped
    read_config_lines "$pinned"
    scan_remotes

    local -A back=()
    while read -r host ip; do [ -n "$ip" ] && back[$ip]=$host; done < <(pin_hostmap "$pinned")

    local i
    for i in "${!R_INDEX[@]}"; do
        mapped=${back[${R_HOST[$i]}]:-}
        [ -n "$mapped" ] || continue
        LINES[${R_INDEX[$i]}]="${R_PREFIX[$i]}${mapped}${R_GAP[$i]}${R_TAIL[$i]}"
    done

    # The header was ours; the config underneath it is what the provider sent.
    : > "$out"
    for line in "${LINES[@]}"; do
        case $line in '# pinned by '*|'#   '*' -> '*|'#   credentials: '*) continue ;; esac
        printf '%s\n' "$line" >> "$out"
    done
}

# One template per pinned base name. The original config is preferred when it
# is still there - it never went through us at all - and a pinned file is the
# fallback.
build_sync_templates() {
    local t base src pinned found=0
    t=$(tmp_dir)/sync
    mkdir -p -- "$t"
    SYNC_FILES=()

    while IFS= read -r base; do
        [ -n "$base" ] || continue
        src=$PATH_IN/$base.ovpn
        if [ -f "$src" ]; then
            cp -- "$src" "$t/$base.ovpn"
            info "$base: from the original config"
        else
            pinned=$(ls -1t "$OUT_DIR/${base}"_*.ovpn 2>/dev/null | head -n1)
            [ -n "$pinned" ] || continue
            if [ -z "$(pin_hostmap "$pinned")" ]; then
                warn "$base: pinned from an address, not a hostname - nothing to re-resolve"
                continue
            fi
            restore_template "$pinned" "$t/$base.ovpn"
            info "$base: hostnames recovered from $(basename -- "$pinned")"
        fi
        SYNC_FILES+=("$t/$base.ovpn")
        found=$((found + 1))
    done < <(sync_bases)

    [ "$found" -gt 0 ]
}

# Everything we have ever pinned: the bases of the files in pinned/, plus
# anything the manifest remembers that has since lost its files.
sync_bases() {
    {
        local f
        for f in "$OUT_DIR"/*.ovpn; do
            [ -e "$f" ] && printf '%s\n' "$(pin_base "$f")"
        done
        [ -f "$(pins_tsv)" ] && awk -F'\t' '{ print $2 }' "$(pins_tsv)" | sed 's/\.ovpn$//'
    } 2>/dev/null | grep -v '^$' | sort -u
}

# What changed for one hostname since the last run, and what it means. This is
# where a run earns its keep: "no answer" has three different causes and only
# one of them is your problem to fix.
# The same row, written without a word about it. Used when a hostname was
# already resolved earlier in this run and only the bookkeeping is left.
manifest_touch() {
    local host=$1 source=$2 port=$3 proto=$4 ips=$5 row first=''
    row=$(tsv_get "$(pins_tsv)" "$host" "$source") && first=$(tsv_field "$row" 6)
    [ -n "$first" ] || first=$(today)
    tsv_put "$(pins_tsv)" "$host" "$source" \
        "$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' "$host" "$source" "$port" "$proto" "$ips" "$first" "$(today)" ok)"
}

report_host_change() {
    local host=$1 source=$2 port=$3 proto=$4 new_ips=$5 rcode=$6
    local row old='' first='' status new gone

    if row=$(tsv_get "$(pins_tsv)" "$host" "$source"); then
        old=$(tsv_field "$row" 5)
        first=$(tsv_field "$row" 6)
    fi
    [ -n "$first" ] || first=$(today)

    if [ -z "$new_ips" ]; then
        case $rcode in
            3)  status=nxdomain
                bad "$host: does not exist any more (NXDOMAIN)"
                info 'The provider retired this hostname - it is not censorship and'
                info 'no re-run will bring it back. Download a fresh .ovpn from your'
                info "provider into $PATH_IN."
                [ -n "$old" ] && info "It used to resolve to $old." ;;
            '') status=lookup-failed
                bad "$host: the lookup itself did not get through"
                info 'That is the DoH path failing, not the hostname. Check the proxy'
                info 'and try again - nothing is known about this name either way.' ;;
            *)  status=no-address
                bad "$host: exists but has no A record right now (rcode $rcode)"
                info 'Either the provider is mid-change or it is IPv6-only. Try again'
                info 'later; the addresses already pinned are untouched.' ;;
        esac
        tsv_put "$(pins_tsv)" "$host" "$source" \
            "$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' "$host" "$source" "$port" "$proto" "$old" "$first" "$(today)" "$status")"
        return 1
    fi

    new=$(csv_minus "$new_ips" "$old")
    gone=$(csv_minus "$old" "$new_ips")

    # An unremarkable answer is worth saying only when you asked what changed.
    if [ -z "$old" ]; then
        [ "$SYNC" -eq 1 ] && ok "$host: $(csv_count "$new_ips") address(es), first time we have seen this name"
    elif [ -z "$new" ] && [ -z "$gone" ]; then
        [ "$SYNC" -eq 1 ] && ok "$host: unchanged ($new_ips)"
    else
        ok "$host: $(csv_count "$new") new, $(csv_count "$gone") gone, $(csv_count "$new_ips") in total"
        [ -n "$new" ]  && info "new:   ${new//,/  }"
        [ -n "$gone" ] && info "gone:  ${gone//,/  }  - anything pinned to those is stale"
    fi

    tsv_put "$(pins_tsv)" "$host" "$source" \
        "$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' "$host" "$source" "$port" "$proto" "$new_ips" "$first" "$(today)" ok)"
    return 0
}

# Files for addresses that are no longer in the answer. Left alone if one of
# them happens to be the tunnel you are on right now - pulling the config out
# from under a live connection helps nobody.
prune_stale() {
    local base=$1 keep=$2 f ip cur removed=0
    cur=$(cat "$ROOT/.state/current" 2>/dev/null)
    for f in "$OUT_DIR/${base}"_*.ovpn; do
        [ -e "$f" ] || continue
        ip=$(basename -- "$f"); ip=${ip%.ovpn}; ip=${ip##*_}
        case ",$keep," in *",$ip,"*) continue ;; esac
        if [ "$f" = "$cur" ]; then
            warn "$(basename -- "$f") is stale but connected right now - left in place"
            continue
        fi
        rm -f -- "$f" && removed=$((removed + 1))
    done
    [ "$removed" -gt 0 ] && info "removed $removed stale file(s) for $base"
    return 0
}


#----------------------------------------------------------------- cloudflare

# Who each pinned address is rented from, and the same list again grouped by
# landlord - which is the part worth reading. Connects to nothing.
show_owners() {
    local pinned_dir=$1 f ip ips=() files=() n known=0 fresh=0 o cc city tag
    head_ 'Who these addresses belong to'

    if [ ! -d "$pinned_dir" ]; then
        info "There is no $pinned_dir yet - run this without --who first and it"
        info 'writes the pinned configs whose addresses this reads.'
        printf '\n'
        return 0
    fi

    while IFS= read -r f; do
        read_config_lines "$f"
        scan_remotes
        [ ${#R_INDEX[@]} -gt 0 ] || continue
        is_ip_literal "${R_HOST[0]}" || continue
        files+=("$(basename -- "$f")")
        ips+=("${R_HOST[0]}")
    done < <(find "$pinned_dir" -maxdepth 1 -type f -name '*.ovpn' | sort)

    if [ ${#ips[@]} -eq 0 ]; then
        info "Nothing in $pinned_dir has an address in it."
        printf '\n'
        return 0
    fi

    load_owners
    local uniq=()
    while IFS= read -r ip; do
        uniq+=("$ip")
        if [ -n "${OWNER_NAME[$ip]:-}" ]; then known=$((known + 1)); else fresh=$((fresh + 1)); fi
    done < <(printf '%s\n' "${ips[@]}" | awk '!seen[$0]++')

    info "${#files[@]} files, ${#uniq[@]} addresses"
    [ "$known" -gt 0 ] && info "$known of them answered from $(owners_tsv) - nothing to ask"
    [ "$fresh" -gt 0 ] && info "$fresh have not been looked up before"

    OWNER_PROXY=$PROXY lookup_owners "${uniq[@]}"

    printf '\n'
    local -A tally=() countries=()
    local missing=0
    for n in "${!files[@]}"; do
        ip=${ips[$n]}
        if o=$(owner_of "$ip"); then
            cc=$(owner_country "$ip"); city=$(owner_city "$ip")
            ok "$(printf '%-52s %-16s %-28s %s' "${files[$n]}" "$ip" "$o" "${city:+$city, }$cc")"
            tally[$o]=$(( ${tally[$o]:-0} + 1 ))
            case " ${countries[$o]:-} " in *" $cc "*) ;; *) countries[$o]="${countries[$o]:-}${cc:+ $cc}" ;; esac
        else
            missing=$((missing + 1))
            warn "$(printf '%-52s %-16s %s' "${files[$n]}" "$ip" '?')"
        fi
    done

    # The point of the whole exercise: four files that share a landlord are not
    # four independent things to try when one of them gets blocked.
    if [ ${#tally[@]} -gt 0 ]; then
        head_ 'By landlord'
        for tag in "${!tally[@]}"; do
            printf '%s\t%s\t%s\n' "${tally[$tag]}" "$tag" "${countries[$tag]:-}"
        done | sort -rn | while IFS=$'\t' read -r n tag cc; do
            local where cnt
            cnt=$(wc -w <<<"$cc")
            if [ "$cnt" -le 4 ]; then where=$(tr ' ' ',' <<<"${cc# }" | sed 's/,/, /g')
            else where="$cnt countries"
            fi
            info "$(printf '%-30s %3d %s   %s' "$tag" "$n" \
                "$([ "$n" -eq 1 ] && printf 'address  ' || printf 'addresses')" "$where")"
        done
    fi

    printf '\n'
    [ "$missing" -gt 0 ] && warn "$missing address(es) could not be looked up - the lookup service did not answer."
    info 'Landlords matter because a site blocking "a VPN" is usually blocking'
    info 'the hosting company, not your provider - so exits sharing one tend to'
    info 'be flagged together, and are not really alternatives to each other.'
    info "Remembered in $(owners_tsv)."
    printf '\n'
}

show_cloudflare_check() {
    local pinned_dir=$1

    head_ 'Where this is measuring from'

    # Ask the kernel which interface it would actually use for a public
    # address, rather than reading the default route. OpenVPN's
    # redirect-gateway def1 does not replace it - it lays 0.0.0.0/1 and
    # 128.0.0.0/1 over the top, which win on longest prefix.
    local dev=''
    if command -v ip >/dev/null 2>&1; then
        dev=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*[[:space:]]dev[[:space:]]\+\([^[:space:]]*\).*/\1/p' | head -n1)
    fi
    if [ -n "$dev" ]; then
        if [[ $dev =~ ^(tun|tap|wg|ppp|nordlynx|proton|ipsec|vti) ]]; then
            ok "traffic leaves over $dev"
        else
            info "traffic leaves over $dev"
            info 'That does not name a tunnel interface - but some clients route without'
            info 'one, so the address Cloudflare reports below is the real answer.'
        fi
    fi

    cf_probe 'https://www.cloudflare.com/cdn-cgi/trace'
    if [ -n "$CF_ERR" ]; then
        printf '\n'; bad "Cloudflare did not answer at all: $CF_ERR"
        info 'That is a dead path, not a reputation problem.'; printf '\n'
        return 0
    fi

    local egress colo loc trace_verdict=$CF_VERDICT
    egress=$(sed -n 's/^ip=//p'   "$CF_BODY" | head -n1)
    colo=$(sed  -n 's/^colo=//p' "$CF_BODY" | head -n1)
    loc=$(sed   -n 's/^loc=//p'  "$CF_BODY" | head -n1)
    info "Cloudflare sees you as ${egress:-unknown}${loc:+ in $loc}${colo:+, via $colo}"

    # If the exit matches an address we pinned, say which file produced it.
    # Plenty of providers NAT the exit to a different address than the one you
    # dialled, so a mismatch is normal rather than a warning sign.
    if [ -n "$egress" ] && [ -d "$pinned_dir" ]; then
        local match
        match=$(grep -lE "^[[:space:]]*remote[[:space:]]+${egress//./\\.}([[:space:]]|$)" \
                    "$pinned_dir"/*.ovpn 2>/dev/null | head -n1)
        if [ -n "$match" ]; then info "that is the exit of $(basename -- "$match")"
        else info 'no pinned config dials that address - normal, most exits are NATed'
        fi
    fi

    head_ 'What Cloudflare serves this exit'

    local names=('www.cloudflare.com' 'speed.cloudflare.com')
    local urls=('https://www.cloudflare.com/cdn-cgi/trace' 'https://speed.cloudflare.com/__down?bytes=1000')
    local h
    while IFS= read -r h; do
        [ -n "$h" ] && { names+=("$h"); urls+=("https://$h/"); }
    done < <(site_hosts "${SITES[@]:-}")

    local i good=0 bad_=0 verdict
    for i in "${!urls[@]}"; do
        if [ "$i" -eq 0 ]; then
            verdict=$trace_verdict
        else
            cf_probe "${urls[$i]}"
            verdict=$CF_VERDICT
        fi
        case $verdict in
            ok)          ok   "$(printf '%-24s %s' "${names[$i]}" 'served')"; good=$((good+1)) ;;
            challenged)  bad  "$(printf '%-24s %s' "${names[$i]}" 'challenge page - this exit is flagged')"; bad_=$((bad_+1)) ;;
            blocked)     bad  "$(printf '%-24s %s' "${names[$i]}" '403 refused')"; bad_=$((bad_+1)) ;;
            unreachable) warn "$(printf '%-24s %s' "${names[$i]}" "no answer - $CF_ERR")" ;;
            *)           warn "$(printf '%-24s %s' "${names[$i]}" "$verdict")" ;;
        esac
    done

    printf '\n'
    if [ "$bad_" -eq 0 ] && [ "$good" -gt 0 ]; then
        ok 'this exit is clean as far as Cloudflare is concerned.'
    elif [ "$good" -eq 0 ]; then
        bad 'Cloudflare refuses everything from this exit.'
        info 'The address has a bad reputation - too many people behind it, or it'
        info 'is a known VPN range. Nothing is wrong with the tunnel, and no'
        info 'setting on this machine changes it.'
        info 'Connect with a different pinned config and run this again.'
    else
        warn 'partly served: some Cloudflare sites answer and others refuse.'
        info 'That is the usual shape of a mildly flagged exit - the rules are'
        info 'per-customer, so a strict site refuses what Cloudflare itself'
        info 'serves. Usable, but expect captchas on the strict ones.'
    fi
    # Remember the verdict against whatever config is connected, so the next
    # run of anything can show it without measuring again. ovpn-connect.sh
    # reads the same table for its menu and its sweep.
    local cur verdict detail
    cur=$(cat "$ROOT/.state/current" 2>/dev/null)
    if [ -n "$cur" ]; then
        if   [ "$bad_" -eq 0 ] && [ "$good" -gt 0 ]; then verdict=clean
        elif [ "$good" -eq 0 ]; then verdict=dirty
        else verdict=partly
        fi
        detail="$good served, $bad_ refused"
        tsv_put "$(exits_tsv)" "$(basename -- "$cur")" '' \
            "$(printf '%s\t%s\t%s\t%s\t%s' "$(basename -- "$cur")" "${egress:-?}" "$verdict" "$(today)" "$detail")"
    fi

    printf '\n'
    info 'This judges the exit you are on right now and nothing else. To compare'
    info 'them all in one go, without connecting to each by hand:'
    info '     ./linux/ovpn-connect.sh --sweep'
    printf '\n'
}


#------------------------------------------------------------------ arguments

# Accept --opt=value as well as --opt value by splitting the first form in two
# and letting one plain loop handle both.
SPLIT=()
for a in "$@"; do
    case $a in
        --*=*) SPLIT+=("${a%%=*}" "${a#*=}") ;;
        *)     SPLIT+=("$a") ;;
    esac
done
set -- ${SPLIT[@]+"${SPLIT[@]}"}

CLI_PATH=''; CLI_OUT=''; CLI_PROXY=''; CLI_RESOLVER=''; CLI_MAXIPS=''
CLI_ENVFILE=''; CLI_AUTHFILE=''; CLI_ADDAUTH=''; CLI_DOHVIA=''
IN_PLACE=0; NO_TEST=0; CHECK_CF=0; NO_AUTH=0; SYNC=0; KEEP_STALE=0; WHO=0
SITES=()

while [ $# -gt 0 ]; do
    case $1 in
        -h|--help)          usage; exit 0 ;;
        -V|--version)       say "$SELF $VERSION"; exit 0 ;;
        -p|--path)          CLI_PATH=${2:?--path needs a value}; shift 2 ;;
        -o|--out-dir)       CLI_OUT=${2:?--out-dir needs a value}; shift 2 ;;
        --proxy)            CLI_PROXY=${2:?--proxy needs a value}; shift 2 ;;
        --doh-via)          CLI_DOHVIA=${2:?--doh-via needs a mode}; shift 2 ;;
        --resolver)         CLI_RESOLVER=${2:?--resolver needs a value}; shift 2 ;;
        -n|--max-ips)       CLI_MAXIPS=${2:?--max-ips needs a value}; shift 2 ;;
        --sync)             SYNC=1; shift ;;
        --keep-stale)       KEEP_STALE=1; shift ;;
        --in-place)         IN_PLACE=1; shift ;;
        --no-test)          NO_TEST=1; shift ;;
        --check-cloudflare) CHECK_CF=1; shift ;;
        --who|--whois)      WHO=1; shift ;;
        --site)             SITES+=("${2:?--site needs a value}"); shift 2 ;;
        --env-file)         CLI_ENVFILE=${2:?--env-file needs a value}; shift 2 ;;
        --auth-file)        CLI_AUTHFILE=${2:?--auth-file needs a value}; shift 2 ;;
        --add-auth)         CLI_ADDAUTH=1; shift ;;
        --no-auth)          NO_AUTH=1; shift ;;
        -*)                 die "unknown option: $1  (try --help)" ;;
        *)                  [ -z "$CLI_PATH" ] || die "unexpected argument: $1"
                            CLI_PATH=$1; shift ;;
    esac
done

command -v curl >/dev/null 2>&1 || die 'curl is required and was not found. Install it (apt install curl / dnf install curl) and run this again.'

banner 'resolve-ovpn-remote' 'pins the remote line of an OpenVPN config to a real IP, over DoH'

ENV_FILE=${CLI_ENVFILE:-$ROOT/.env}
load_env_file "$ENV_FILE"

PATH_IN=${CLI_PATH:-$(env_or OVPN_CONFIG_DIR "$ROOT/configs")}
OUT_DIR=${CLI_OUT:-$(env_or OVPN_OUT_DIR "$ROOT/pinned")}
PROXY=${CLI_PROXY:-$(env_or OVPN_PROXY '')}
RESOLVER=${CLI_RESOLVER:-$(env_or OVPN_RESOLVER 'cloudflare')}
MAX_IPS=${CLI_MAXIPS:-$(env_or OVPN_MAX_IPS 4)}
AUTH_FILE=${CLI_AUTHFILE:-$(env_or OVPN_AUTH_FILE "$ROOT/.ovpn-auth")}
ADD_AUTH=${CLI_ADDAUTH:-$(env_or OVPN_ADD_AUTH 0)}
AUTH_USER=$(env_or OVPN_USER '')
AUTH_PASS=$(env_or OVPN_PASS '')
DOH_VIA=${CLI_DOHVIA:-$(env_or OVPN_DOH_VIA 'auto')}
[ ${#SITES[@]} -eq 0 ] && { s=$(env_or OVPN_SITE ''); [ -n "$s" ] && SITES=("$s"); }

case $DOH_VIA in
    auto|direct|proxy) ;;
    *) die "--doh-via must be auto, direct or proxy - not '$DOH_VIA'" ;;
esac
[ -n "${DOH_ENDPOINTS[$RESOLVER]:-}" ] || die "--resolver must be cloudflare or google, not '$RESOLVER'"
[[ $MAX_IPS =~ ^[0-9]+$ ]] && [ "$MAX_IPS" -ge 1 ] && [ "$MAX_IPS" -le 32 ] \
    || die "--max-ips must be a number between 1 and 32, not '$MAX_IPS'"
[ "$ADD_AUTH" = 1 ] || ADD_AUTH=0

# An auth file only makes sense as an absolute path: OpenVPN reads it relative
# to whatever directory it happens to be started in, which is rarely this one.
case $AUTH_FILE in /*) ;; *) AUTH_FILE=$ROOT/${AUTH_FILE#./} ;; esac


#----------------------------------------------------------------------- main

if [ "$CHECK_CF" -eq 1 ]; then
    show_cloudflare_check "$OUT_DIR"
    exit 0
fi

if [ "$WHO" -eq 1 ]; then
    show_owners "$OUT_DIR"
    exit 0
fi

# Make the inbox rather than complain about it: a first run with nothing in it
# should leave you with somewhere obvious to put the files.
if [ ! -e "$PATH_IN" ]; then
    if [ "$PATH_IN" = "$ROOT/configs" ]; then mkdir -p -- "$PATH_IN"
    else die "No such path: $PATH_IN"
    fi
fi

FILES=()
if [ "$SYNC" -eq 1 ]; then
    head_ 'Sync'
    info 'Re-resolving the hostnames these files were pinned from.'
    SYNC_FILES=()
    build_sync_templates || die "nothing to sync: no pinned files in $OUT_DIR and nothing remembered. Run this without --sync first."
    FILES=("${SYNC_FILES[@]}")
elif [ -d "$PATH_IN" ]; then
    while IFS= read -r f; do FILES+=("$f"); done < <(find "$PATH_IN" -maxdepth 1 -type f -name '*.ovpn' | sort)
else
    FILES=("$PATH_IN")
fi

# An empty inbox on a first run is not an error, it is the setup step.
if [ ${#FILES[@]} -eq 0 ]; then
    head_ 'Nothing to do yet'
    info 'Put the .ovpn files you downloaded from your provider into:'
    info "     $PATH_IN"
    info 'then run this again.'
    printf '\n'
    info 'Already have pinned files and only want fresh addresses for them?'
    info "     ./$SELF --sync"
    printf '\n'
    exit 0
fi

[ "$IN_PLACE" -eq 1 ] || mkdir -p -- "$OUT_DIR" || die "cannot create $OUT_DIR"

head_ 'Resolver'
ROUTE=''
select_doh_route
info "provider: $RESOLVER (${DOH_ENDPOINTS[$RESOLVER]})"

# Credentials, if there are any. Written once for the whole run.
head_ 'Credentials'
USE_AUTH=0
if [ "$NO_AUTH" -eq 1 ]; then
    info '--no-auth: leaving auth-user-pass alone, the client will ask as usual'
elif [ -n "$AUTH_USER" ] && [ -n "$AUTH_PASS" ]; then
    write_auth_file "$AUTH_FILE"
    USE_AUTH=1
    ok "written to $AUTH_FILE (mode 600)"
    info "user: $AUTH_USER"
    info 'every pinned config will point at it, so the client stops asking.'
elif [ -n "$AUTH_USER" ] || [ -n "$AUTH_PASS" ]; then
    warn "only half the credentials are set in $ENV_FILE - both OVPN_USER and OVPN_PASS are needed"
else
    info "no credentials in $(basename -- "$ENV_FILE") - the client will ask on connect."
    info "To stop that, put OVPN_USER and OVPN_PASS in $ENV_FILE (see .env.example)."
fi

head_ "Configs (${#FILES[@]})"

probe_devtcp
declare -A CACHE=()
WRITTEN=0
SKIPPED=0
AUTH_MISSING=0
LAST_WRITTEN=''
DEAD_NOTE=0
PROXY_NOTE=0

for f in "${FILES[@]}"; do
    name=$(basename -- "$f")

    # Keep whatever line ending the file arrived with. Rewriting a config from
    # LF to CRLF changes every line of it, which makes the one edit that
    # matters impossible to see in a diff.
    head_bytes=$(head -c 4096 -- "$f")
    case $head_bytes in *$'\r'*) NL=$'\r\n' ;; *) NL=$'\n' ;; esac
    read_config_lines "$f"

    scan_remotes
    if [ ${#R_INDEX[@]} -eq 0 ]; then
        warn "$name: no remote line, skipped"
        SKIPPED=$((SKIPPED+1))
        continue
    fi

    # Resolve each distinct hostname once across the whole run. A folder of
    # configs for one provider repeats the same names constantly.
    declare -A RESOLVED=()
    for i in "${!R_HOST[@]}"; do
        h=${R_HOST[$i]}
        hport=$(remote_port "${R_TAIL[$i]}")
        hproto=$(remote_proto "${R_TAIL[$i]}")

        if is_ip_literal "$h"; then RESOLVED[$h]=$h; continue; fi
        if [ -n "${CACHE[$h]+set}" ]; then
            RESOLVED[$h]=${CACHE[$h]}
            manifest_touch "$h" "$name" "$hport" "$hproto" "${CACHE[$h]// /,}"
            continue
        fi

        ips=$(resolve_doh "$h" "$ROUTE") || ips=''
        rcode=$(cat "$(tmp_dir)/rcode" 2>/dev/null)
        clean=(); forged=()
        for ip in $ips; do
            if is_reserved_ip "$ip"; then forged+=("$ip"); else clean+=("$ip"); fi
        done
        if [ ${#forged[@]} -gt 0 ]; then
            warn "$h: threw away ${forged[*]} - not a public address"
            info 'That is a forged answer, which is the whole reason for this script.'
        fi

        csv=$(printf '%s,' "${clean[@]:-}"); csv=${csv%,}; csv=${csv#,}
        report_host_change "$h" "$name" "$hport" "$hproto" "$csv" "$rcode" || true

        CACHE[$h]=${clean[*]:-}
        RESOLVED[$h]=${clean[*]:-}
    done

    empty=()
    for h in "${R_HOST[@]}"; do
        [ -z "${RESOLVED[$h]}" ] && empty+=("$h")
    done
    if [ ${#empty[@]} -gt 0 ]; then
        bad "$name: could not resolve $(printf '%s\n' "${empty[@]}" | sort -u | paste -sd, -)"
        SKIPPED=$((SKIPPED+1))
        continue
    fi

    # How many variants to write: the most addresses any one hostname in this
    # file has. A file whose hosts resolve to different counts reuses the last
    # address of the shorter ones rather than dropping a remote.
    count=1
    for h in "${R_HOST[@]}"; do
        n=$(wc -w <<<"${RESOLVED[$h]}")
        [ "$n" -gt "$count" ] && count=$n
    done
    [ "$count" -gt "$MAX_IPS" ] && count=$MAX_IPS
    [ "$IN_PLACE" -eq 1 ] && count=1

    for (( v = 0; v < count; v++ )); do
        COPY=("${LINES[@]}")
        first_ip=''; first_port=''; first_proto=''
        header=("# pinned by resolve-ovpn-remote on $(date '+%Y-%m-%d %H:%M') - $RESOLVER over DoH")

        for i in "${!R_INDEX[@]}"; do
            h=${R_HOST[$i]}
            read -r -a ips_arr <<<"${RESOLVED[$h]}"
            idx=$(( v < ${#ips_arr[@]} ? v : ${#ips_arr[@]} - 1 ))
            ip=${ips_arr[$idx]}
            COPY[${R_INDEX[$i]}]="${R_PREFIX[$i]}${ip}${R_GAP[$i]}${R_TAIL[$i]}"
            [ "$h" != "$ip" ] && header+=("#   $h -> $ip")
            if [ -z "$first_ip" ]; then
                first_ip=$ip
                first_port=$(remote_port "${R_TAIL[$i]}")
                first_proto=$(remote_proto "${R_TAIL[$i]}")
            fi
        done

        if [ "$USE_AUTH" -eq 1 ]; then
            if apply_auth "$AUTH_FILE"; then
                header+=("#   credentials: $AUTH_FILE")
            else
                AUTH_MISSING=$((AUTH_MISSING+1))
            fi
        fi

        if [ "$IN_PLACE" -eq 1 ]; then
            target=$f
        else
            target="$OUT_DIR/${name%.ovpn}_${first_ip}.ovpn"
        fi

        # The config can carry a private key, so it is nobody else's business.
        ( umask 077
          : > "$target"
          for line in "${header[@]}" "${COPY[@]}"; do printf '%s%s' "$line" "$NL"; done >> "$target"
        ) || die "cannot write $target"
        WRITTEN=$((WRITTEN+1))
        LAST_WRITTEN=$target

        note=''
        reach=''
        if [ "$NO_TEST" -eq 0 ]; then
            case $first_proto in
                udp*)
                    # A UDP port cannot be probed: OpenVPN drops any datagram
                    # without a valid tls-auth HMAC, so silence means "blocked"
                    # and "working" equally. Saying nothing beats guessing.
                    note='  (udp - not testable)' ;;
                *)
                    # Not just up or down: an address this line refuses but the
                    # proxy reaches is a live server behind a block, and one
                    # neither can reach is a server that has moved on. They
                    # need different things done about them.
                    if tcp_reachable "$first_ip" "$first_port" "${PROBE_TIMEOUT:-8}"; then
                        reach=direct
                    else
                        [ "$PROBE_PROXY_TRIED" -eq 0 ] && info 'that address did not answer - looking for a proxy to ask...'
                        reach=$(diagnose_address "$first_ip" "$first_port" "$first_proto" "$(probe_proxy_url)")
                    fi
                    case $reach in
                        direct)     note='  reachable' ;;
                        proxy-only) note='  only through the proxy' ;;
                        dead)       note='  NOT reachable - and not through the proxy either' ;;
                        no-answer)  note='  NOT reachable' ;;
                        *)          note='  (no way to test - install netcat)' ;;
                    esac ;;
            esac
        fi

        out_name=$(basename -- "$target")
        case $reach in
            dead|no-answer)
                warn "$(printf '%s  %s:%s%s' "$out_name" "$first_ip" "$first_port" "$note")"
                [ "$DEAD_NOTE" -eq 0 ] && {
                    if [ "$reach" = dead ]; then
                        info 'the proxy cannot reach it either, so it is the server that has'
                        info "gone, not your line. ./$SELF --sync for fresh addresses."
                    else
                        info 'there was no proxy to ask, so whether the address is blocked or'
                        info 'the server is gone is still open. Start your proxy and re-run'
                        info 'to find out which.'
                    fi
                    DEAD_NOTE=1
                } ;;
            proxy-only)
                warn "$(printf '%s  %s:%s%s' "$out_name" "$first_ip" "$first_port" "$note")"
                [ "$PROXY_NOTE" -eq 0 ] && {
                    info 'the server is alive but your line will not dial it - the address'
                    info 'itself is blocked, not just its DNS. Connect it with'
                    info '     ./linux/ovpn-connect.sh --via-proxy'
                    PROXY_NOTE=1
                } ;;
            *)
                ok "$(printf '%s  %s:%s%s' "$out_name" "$first_ip" "$first_port" "$note")" ;;
        esac
    done

    # Files left over from addresses this hostname no longer answers with.
    if [ "$SYNC" -eq 1 ] && [ "$KEEP_STALE" -eq 0 ] && [ "$IN_PLACE" -eq 0 ]; then
        keep=''
        for h in "${R_HOST[@]}"; do keep="${keep:+$keep,}${RESOLVED[$h]// /,}"; done
        prune_stale "${name%.ovpn}" "$keep"
    fi
    unset RESOLVED
done

head_ 'Done'
ok "$WRITTEN file(s) written$([ "$SKIPPED" -gt 0 ] && printf ', %s skipped' "$SKIPPED")"
if [ "$IN_PLACE" -eq 0 ]; then
    info "in: $OUT_DIR"
    info 'The originals were not touched.'
fi

if [ "$USE_AUTH" -eq 1 ] && [ "$AUTH_MISSING" -gt 0 ]; then
    printf '\n'
    warn "$AUTH_MISSING file(s) have no auth-user-pass line, so the credentials went unused."
    info 'Those configs authenticate by certificate alone. If your provider does'
    info 'want a username, re-run with --add-auth to have the line added.'
fi

printf '\n'
info 'Import one of these into your OpenVPN client and connect. Nothing in'
info 'it depends on your resolver any more, so a poisoned answer cannot'
info 'send it to the wrong address.'
printf '\n'
if [ -n "$LAST_WRITTEN" ] && [ "$IN_PLACE" -eq 0 ]; then
    info 'From the command line:'
    info "     sudo openvpn --config '$LAST_WRITTEN'"
    printf '\n'
fi
info 'Where a hostname gave several addresses you have a file for each.'
info 'They are alternatives, not a ranking - if one stops answering, try'
info 'the next. Re-run this when they all go stale: providers move'
info 'addresses, and a pinned file cannot follow them.'
printf '\n'
info 'Once connected, check whether that exit is one Cloudflare will serve:'
info "     ./$SELF --check-cloudflare"
printf '\n'
