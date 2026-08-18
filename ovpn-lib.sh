#!/usr/bin/env bash
#
# ovpn-lib.sh - what resolve-ovpn-remote.sh and ovpn-connect.sh both need.
#
# Sourced, never run. Nothing in here prints unless you call it, and nothing
# in here parses arguments - the two scripts do that themselves.
#
# The .env parser lives here for one reason above the others: it carries the
# list of keys a .env may set, and two copies of that list would drift the
# first time either script learned a new one.

# Twenty seconds is long for a probe, but a blocked path fails slowly and we
# would rather wait than call a working server dead. A script may set its own
# before sourcing this.
: "${TIMEOUT:=20}"

# Ports worth trying for a local proxy, in rough order of popularity.
PROXY_PORTS=(10808 10809 7890 7891 2080 2081 1080 1081 8889 8080 20171 12334)

# Everything a public hostname has no business resolving to. Rejecting the
# whole of RFC1918 and friends rather than a list of known-forged addresses
# means this keeps working when the censor picks a different one tomorrow.
RESERVED=(
    '0.0.0.0 8'
    '10.0.0.0 8'
    '127.0.0.0 8'
    '169.254.0.0 16'
    '172.16.0.0 12'
    '192.168.0.0 16'
    '224.0.0.0 4'
)


#--------------------------------------------------------------------- output

if [ -t 1 ]; then
    C_OFF=$'\033[0m'; C_CYAN=$'\033[36m'; C_DCYAN=$'\033[36;2m'
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'
    C_GRAY=$'\033[90m'; C_WHITE=$'\033[97m'
else
    C_OFF=''; C_CYAN=''; C_DCYAN=''; C_GREEN=''; C_RED=''; C_YELLOW=''
    C_GRAY=''; C_WHITE=''
fi

say()   { printf '%s\n' "$*"; }
head_() { local t=$1; printf '\n%s  %s%s\n' "$C_CYAN" "$t" "$C_OFF"
          printf '%s  %s%s\n' "$C_DCYAN" "$(printf '%*s' "${#t}" '' | tr ' ' '-')" "$C_OFF"; }
ok()    { printf '%s  [ ok ] %s%s\n' "$C_GREEN"  "$C_OFF" "$*"; }
bad()   { printf '%s  [fail] %s%s\n' "$C_RED"    "$C_OFF" "$*"; }
warn()  { printf '%s  [warn] %s%s\n' "$C_YELLOW" "$C_OFF" "$*"; }
info()  { printf '         %s%s%s\n' "$C_GRAY" "$*" "$C_OFF"; }
die()   { printf '\n'; bad "$*"; printf '\n'; exit 1; }

banner() {
    printf '\n%s  %s%s\n' "$C_WHITE" "$1" "$C_OFF"
    printf '%s  %s%s\n' "$C_GRAY" "$2" "$C_OFF"
}


#------------------------------------------------------------------ addresses

ip_to_int() {
    local IFS=. a b c d
    read -r a b c d <<<"$1"
    printf '%s' $(( (10#$a << 24) | (10#$b << 16) | (10#$c << 8) | 10#$d ))
}

is_ip_literal() { [[ $1 =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; }

is_reserved_ip() {
    local ip=$1 v r net bits mask
    v=$(ip_to_int "$ip")
    for r in "${RESERVED[@]}"; do
        net=${r% *}; bits=${r#* }
        mask=$(( bits == 0 ? 0 : ((0xFFFFFFFF << (32 - bits)) & 0xFFFFFFFF) ))
        if (( (v & mask) == ($(ip_to_int "$net") & mask) )); then return 0; fi
    done
    return 1
}

is_hostname() { [[ $1 =~ ^[A-Za-z0-9._-]+$ ]]; }


#------------------------------------------------------------------------ DoH

: "${RESOLVER:=cloudflare}"

declare -A DOH_ENDPOINTS=(
    [cloudflare]='https://cloudflare-dns.com/dns-query'
    [google]='https://dns.google/resolve'
)

# Both providers answer with the same JSON shape. Reading the addresses out
# with grep rather than jq keeps this to tools every distro already has - and
# an A query can only answer with A records and CNAMEs, so matching the
# dotted-quad shape is enough to tell them apart.
#
# DOH_RCODE is left behind for the caller: an empty answer means one thing if
# the name does not exist (rcode 3, the provider retired it) and quite another
# if the lookup itself never got through (no rcode at all). It is written to
# disk as well, because callers read the addresses through a command
# substitution and a subshell cannot hand a variable back.
DOH_RCODE=''
resolve_doh() {
    local name=$1 proxy=$2 url args body
    DOH_RCODE=''
    : > "$(tmp_dir)/rcode"
    is_hostname "$name" || return 1

    url="${DOH_ENDPOINTS[$RESOLVER]}?name=${name}&type=A"
    args=(-sS --max-time "$TIMEOUT" -H 'Accept: application/dns-json' -A 'ovpn-pin')
    # An explicit --noproxy '*' matters: curl reads http_proxy from the
    # environment otherwise, which is not what an omitted --proxy asked for.
    if [ -n "$proxy" ]; then args+=(--proxy "$proxy"); else args+=(--noproxy '*'); fi

    body=$(curl "${args[@]}" "$url" 2>/dev/null) || return 1
    DOH_RCODE=$(grep -oE '"Status"[[:space:]]*:[[:space:]]*[0-9]+' <<<"$body" | head -n1 | grep -oE '[0-9]+$')
    printf '%s' "$DOH_RCODE" > "$(tmp_dir)/rcode"
    grep -oE '"data"[[:space:]]*:[[:space:]]*"[0-9]{1,3}(\.[0-9]{1,3}){3}"' <<<"$body" \
        | grep -oE '[0-9]{1,3}(\.[0-9]{1,3}){3}' \
        | awk '!seen[$0]++'
}


#---------------------------------------------------------------------- proxy

# A liveness probe, not a benchmark: ask the candidate proxy to fetch one DNS
# answer and see whether anything that looks like an address comes back.
test_proxy() {
    local port=$1 endpoint=${2:-https://cloudflare-dns.com/dns-query} out
    out=$(curl -sS --proxy "http://127.0.0.1:$port" --connect-timeout 2 --max-time 8 \
              -H 'Accept: application/dns-json' -A 'ovpn-pin' \
              "$endpoint?name=example.com&type=A" 2>/dev/null) || return 1
    grep -qE '"data"[[:space:]]*:[[:space:]]*"[0-9]{1,3}(\.[0-9]{1,3}){3}"' <<<"$out"
}

find_proxy() {
    local p endpoint=${1:-}
    for p in "${PROXY_PORTS[@]}"; do
        if test_proxy "$p" "$endpoint"; then printf 'http://127.0.0.1:%s' "$p"; return 0; fi
    done
    return 1
}

# host and port, space separated, out of an http://host:port or host:port.
split_proxy_url() {
    local u=${1#*://} h p
    u=${u%%/*}
    u=${u##*@}
    h=${u%:*}; p=${u##*:}
    [ -n "$h" ] || h=127.0.0.1
    [[ $p =~ ^[0-9]+$ ]] || p=8080
    printf '%s %s' "$h" "$p"
}


#--------------------------------------------------------------- config lines

# OpenVPN accepts `remote HOST [PORT] [PROTO]`, several of them as a failover
# list, and the same line inside <connection> blocks. Everything after the
# hostname is kept exactly as it was - the port and protocol are not ours to
# reinterpret.
#
# Reads the global LINES array, fills the parallel R_* ones.
scan_remotes() {
    local i line
    R_INDEX=(); R_PREFIX=(); R_HOST=(); R_GAP=(); R_TAIL=()
    for i in "${!LINES[@]}"; do
        line=${LINES[$i]}
        if [[ $line =~ ^([[:space:]]*remote[[:space:]]+)([^[:space:]]+)([[:space:]]*)(.*)$ ]]; then
            R_INDEX+=("$i")
            R_PREFIX+=("${BASH_REMATCH[1]}")
            R_HOST+=("${BASH_REMATCH[2]}")
            R_GAP+=("${BASH_REMATCH[3]:- }")
            R_TAIL+=("${BASH_REMATCH[4]}")
        fi
    done
}

# Read a config into LINES with its carriage returns stripped, so every
# consumer sees the same thing whatever the file arrived as.
read_config_lines() {
    LINES=()
    mapfile -t LINES < <(sed 's/\r$//' -- "$1")
}

remote_port() {
    local tail=$1 line
    if [[ $tail =~ ^[[:space:]]*([0-9]+) ]]; then printf '%s' "${BASH_REMATCH[1]}"; return; fi
    for line in "${LINES[@]}"; do
        if [[ $line =~ ^[[:space:]]*port[[:space:]]+([0-9]+) ]]; then
            printf '%s' "${BASH_REMATCH[1]}"; return
        fi
    done
    printf '1194'
}

remote_proto() {
    local tail=$1 line
    if [[ $tail =~ (^|[[:space:]])(tcp-client|tcp4|tcp6|tcp|udp4|udp6|udp)([[:space:]]|$) ]]; then
        printf '%s' "${BASH_REMATCH[2]}"; return
    fi
    for line in "${LINES[@]}"; do
        if [[ $line =~ ^[[:space:]]*proto[[:space:]]+([^[:space:]]+) ]]; then
            printf '%s' "${BASH_REMATCH[1]}"; return
        fi
    done
    printf 'udp'
}


#------------------------------------------------------------------- reaching

# bash speaks TCP itself on every distro build, but the feature can be compiled
# out, so find out once rather than reporting every server unreachable.
HAVE_DEVTCP=0
probe_devtcp() {
    local err
    err=$( { exec 3<>/dev/tcp/127.0.0.1/1; } 2>&1 )
    # Note the braces. `exec 3>&- 2>/dev/null` would close the descriptor and
    # then point this shell's stderr at /dev/null for the rest of the run,
    # which swallows every error message after it.
    { exec 3>&-; } 2>/dev/null
    [[ $err != *'No such file'* ]] && HAVE_DEVTCP=1
    return 0
}

# 0 reachable, 1 not, 2 no way to tell. Goes out over whatever the default
# route is and through no proxy at all, which is the whole point of it: it
# answers "can this machine dial that address itself".
tcp_reachable() {
    local ip=$1 port=$2 t=${3:-$TIMEOUT}
    if [ "$HAVE_DEVTCP" -eq 1 ]; then
        timeout "$t" bash -c "exec 3<>/dev/tcp/$ip/$port" 2>/dev/null && return 0
        return 1
    fi
    if command -v nc >/dev/null 2>&1; then
        nc -z -w "$t" "$ip" "$port" >/dev/null 2>&1 && return 0
        return 1
    fi
    return 2
}


#---------------------------------------------------------------- environment

declare -A DOTENV=()

# Read KEY=VALUE by hand rather than sourcing the file. A .env holding a
# password has no business being executed, and one stray backtick in a
# password would run as a command if it were.
load_env_file() {
    local file=$1 line key val
    [ -f "$file" ] || return 0

    local mode
    mode=$(stat -c '%a' "$file" 2>/dev/null || printf '')
    if [ -n "$mode" ] && [ "${mode: -2}" != "00" ]; then
        warn "$file is readable by others - chmod 600 it"
    fi

    while IFS= read -r line || [ -n "$line" ]; do
        line=${line%$'\r'}
        line=${line#"${line%%[![:space:]]*}"}
        [ -z "$line" ] && continue
        case $line in
            '#'*) continue ;;
            'export '*) line=${line#export } ;;
        esac
        [[ $line == *=* ]] || continue

        key=${line%%=*}; val=${line#*=}
        key=${key//[[:space:]]/}
        val=${val%"${val##*[![:space:]]}"}
        if   [[ $val =~ ^\"(.*)\"$ ]]; then val=${BASH_REMATCH[1]}
        elif [[ $val =~ ^\'(.*)\'$ ]]; then val=${BASH_REMATCH[1]}
        fi

        case $key in
            OVPN_USER|OVPN_PASS|OVPN_PROXY|OVPN_RESOLVER|OVPN_MAX_IPS|\
            OVPN_CONFIG_DIR|OVPN_OUT_DIR|OVPN_AUTH_FILE|OVPN_ADD_AUTH|OVPN_SITE|\
            OVPN_PROXY_OFF_CMD|OVPN_PROXY_ON_CMD|OVPN_PROXY_UPSTREAM|\
            OVPN_FALLBACK|OVPN_SET_DNS|OVPN_DOH_VIA|OVPN_CONNECT_VIA|\
            OVPN_KILL_SWITCH)
                DOTENV[$key]=$val ;;
        esac
    done < "$file"
}

# Precedence, loosest first: built-in default, then .env, then a variable
# already exported in the shell, then the command line.
env_or() {
    local key=$1 fallback=$2
    if [ -n "${!key:-}" ]; then printf '%s' "${!key}"
    elif [ -n "${DOTENV[$key]:-}" ]; then printf '%s' "${DOTENV[$key]}"
    else printf '%s' "$fallback"
    fi
}


#----------------------------------------------------------------- scratch

# One temp directory per run, made here rather than on first use. Making it
# lazily looks tidier right up until the first call happens inside a $( ) -
# then the directory belongs to a subshell, whose EXIT trap deletes it while
# the parent still thinks it has one.
#
# The trap is set here and nowhere else: two scripts each setting their own
# would mean one of them silently losing its cleanup.
TMP=$(mktemp -d) || { printf '\n  [fail] cannot create a temp directory\n\n' >&2; exit 1; }
trap 'rm -rf -- "$TMP"' EXIT

tmp_dir() { printf '%s' "$TMP"; }

today() { date '+%Y-%m-%d'; }


#---------------------------------------------------------------- remembering

# What was resolved last time, and what each exit turned out to be like. Two
# tab-separated tables, because they have to survive being read by awk, edited
# by a person, and diffed - and because a dependency on jq to remember six
# fields would be a poor trade.
#
#   pins.tsv    host  source  port  proto  ips  first_seen  last_seen  status
#   exits.tsv   file  ip  verdict  checked  detail
#
# Keyed on the first two fields (pins) or the first (exits).

state_dir() {
    local d=$ROOT/.state
    [ -d "$d" ] || mkdir -p -- "$d" 2>/dev/null || die "cannot create $d"
    printf '%s' "$d"
}

pins_tsv()  { printf '%s/pins.tsv'  "$(state_dir)"; }
exits_tsv() { printf '%s/exits.tsv' "$(state_dir)"; }

# Whole line for a key, or nothing.
tsv_get() {
    local file=$1 k1=$2 k2=${3:-}
    [ -f "$file" ] || return 1
    awk -F'\t' -v a="$k1" -v b="$k2" -v n="$#" \
        '$1 == a && (n < 3 || $2 == b) { print; found = 1; exit } END { exit !found }' "$file"
}

# Replace the row with this key, or add it. Written to a temp and moved, so an
# interrupted run cannot leave half a table.
tsv_put() {
    local file=$1 k1=$2 k2=$3 line=$4 tmp
    tmp=$(tmp_dir)/tsv.$$
    if [ -f "$file" ]; then
        awk -F'\t' -v a="$k1" -v b="$k2" '!($1 == a && ($2 == b || b == ""))' "$file" > "$tmp"
    else
        : > "$tmp"
    fi
    printf '%s\n' "$line" >> "$tmp"
    mv -f -- "$tmp" "$file"
}

tsv_field() { awk -F'\t' -v n="$2" '{ print $n }' <<<"$1"; }

# a,b,c and b,c,d -> what is only in the first. Used both ways round to get
# "new since last time" and "gone since last time" out of one function.
csv_minus() {
    local a=$1 b=$2 x out=''
    for x in ${a//,/ }; do
        case ",$b," in *",$x,"*) ;; *) out="${out:+$out,}$x" ;; esac
    done
    printf '%s' "$out"
}

csv_count() {
    local c=$1
    [ -z "$c" ] && { printf '0'; return; }
    printf '%s' "$(tr ',' '\n' <<<"$c" | grep -c .)"
}


#--------------------------------------------------------------- probing more

# Does the proxy manage to reach this address? A CONNECT is exactly the
# question OpenVPN would be asking it, and the answer tells the two failures
# apart: an address your line refuses but the proxy reaches is alive and
# blocked, and one neither can reach is simply gone.
#
# 0 reachable through the proxy, 1 not, 2 no way to tell.
proxy_connect_ok() {
    local proxy=$1 ip=$2 port=$3 ph pp rc
    [ -n "$proxy" ] || return 2
    [ "$HAVE_DEVTCP" -eq 1 ] || return 2
    read -r ph pp <<<"$(split_proxy_url "$proxy")"

    # The 200 alone proves nothing. v2ray, Xray and most of that family answer
    # CONNECT the moment they have parsed it and dial the far end afterwards,
    # so a dead address gets the same cheerful 200 as a live one - and then the
    # tunnel is closed a moment later when the dial fails. That close is the
    # real answer, so wait for it: silence on an open connection means the far
    # end is there and saying nothing, which is exactly what an OpenVPN server
    # does to anyone who has not sent it a valid HMAC.
    timeout 20 bash -c '
        ph=$1; pp=$2; ip=$3; port=$4
        exec 3<>/dev/tcp/$ph/$pp || exit 2
        printf "CONNECT %s:%s HTTP/1.1\r\nHost: %s:%s\r\n\r\n" "$ip" "$port" "$ip" "$port" >&3
        IFS= read -r -t 8 line <&3 || exit 2
        case $line in "HTTP/1.0 200"*|"HTTP/1.1 200"*) ;; *) exit 1 ;; esac
        while IFS= read -r -t 3 line <&3; do
            case $line in ""|$'"'"'\r'"'"') break ;; esac
        done
        IFS= read -r -t 5 line <&3
        rc=$?
        [ $rc -eq 0 ] && exit 0      # it sent something: certainly connected
        [ $rc -gt 128 ] && exit 0    # said nothing, still open: connected
        exit 1                       # closed on us: the dial failed
    ' _ "$ph" "$pp" "$ip" "$port" 2>/dev/null
    rc=$?
    [ "$rc" -eq 124 ] && return 2
    return "$rc"
}

# The proxy is consulted only once the direct path has already failed, and the
# four answers are deliberately different things:
#
#   direct      this machine dials it. Nothing more to think about.
#   proxy-only  the proxy reaches it and we do not - a live server behind a
#               block on the address itself.
#   dead        neither reaches it - the server, not the line.
#   no-answer   we cannot reach it and there was no proxy to ask, so which of
#               the two above it is remains unknown. Saying "dead" here would
#               be a guess dressed as a finding.
#   untestable  udp, or no way to open a socket at all.
diagnose_address() {
    local ip=$1 port=$2 proto=$3 proxy=${4:-}
    if [[ $proto == udp* ]]; then printf 'untestable'; return 0; fi
    if tcp_reachable "$ip" "$port" "${PROBE_TIMEOUT:-8}"; then printf 'direct'; return 0; fi
    case $(proxy_connect_ok "$proxy" "$ip" "$port"; printf '%s' "$?") in
        0) printf 'proxy-only' ;;
        1) printf 'dead' ;;
        *) printf 'no-answer' ;;
    esac
}


#--------------------------------------------------------------------- owners

# Who an address actually belongs to. A VPN provider owns almost none of its
# racks - it rents them from M247, Datacamp, Leaseweb, Cyberzone - and it is
# that landlord's name, not your provider's, that a site sees and blocks when
# it blocks "a VPN". Two things follow: exits sharing a landlord tend to be
# flagged together, and four addresses that turn out to be one operator in one
# city are not four alternatives.
#
# ip-api.com answers a hundred addresses in one request and names the operator
# outright - "M247", not a registry handle. Its free tier is HTTP only, so the
# query crosses the line in clear: a list of VPN addresses, to a third party.
# Nothing in it your DNS and SNI did not already say, but you should be the one
# deciding that, which is what --no-owner is for. If the plain request does not
# come back at all, ipwho.is is asked over HTTPS instead, one address at a time.
#
#   owners.tsv   ip  asn  owner  country  city  checked
#
# The same table the Windows half writes, down to the column order and the
# country being spelled out rather than coded - one of the two would otherwise
# be reading the other's rows and quietly getting them wrong.
#
# An address nobody will name is written down as '-' rather than left out, so
# the next run does not spend the same minute finding that out again.

owners_tsv() { printf '%s/owners.tsv' "$(state_dir)"; }

declare -A OWNER_NAME=() OWNER_ASN=() OWNER_CC=() OWNER_CITY=() OWNER_WHEN=()
OWNERS_LOADED=0
OWNERS_DIRTY=0

load_owners() {
    [ "$OWNERS_LOADED" -eq 1 ] && return 0
    OWNERS_LOADED=1
    local f ip asn name cc city when size
    f=$(owners_tsv)
    [ -f "$f" ] || return 0

    # A row is about eighty bytes and there is one per address, so a table in
    # the megabytes is not a table any more. Throw it away rather than load it:
    # it is a cache, and the next run rebuilds it.
    size=$(stat -c %s -- "$f" 2>/dev/null || printf 0)
    if [ "$size" -gt 5000000 ]; then
        warn "$f had grown to $((size / 1000000))MB, which is not a table of addresses - starting again"
        rm -f -- "$f"
        return 0
    fi

    while IFS=$'\t' read -r ip asn name cc city when; do
        [ -n "$ip" ] || continue
        OWNER_ASN[$ip]=$asn; OWNER_NAME[$ip]=$name
        OWNER_CC[$ip]=$cc;   OWNER_CITY[$ip]=$city
        OWNER_WHEN[$ip]=$when
    done < "$f"
    return 0
}

save_owners() {
    [ "$OWNERS_DIRTY" -eq 1 ] || return 0
    local f tmp ip
    f=$(owners_tsv); tmp=$(tmp_dir)/owners.$$
    : > "$tmp"
    for ip in "${!OWNER_NAME[@]}"; do
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$ip" "${OWNER_ASN[$ip]}" "${OWNER_NAME[$ip]}" \
            "${OWNER_CC[$ip]}" "${OWNER_CITY[$ip]}" "${OWNER_WHEN[$ip]:-$(today)}" >> "$tmp"
    done
    sort -t$'\t' -k1,1 "$tmp" -o "$tmp"
    mv -f -- "$tmp" "$f"
    OWNERS_DIRTY=0
}

# One JSON object per line, then the fields out of it. Same grep-not-jq
# reasoning as the DoH parser: only flat fields are asked for, so an object is
# everything between one pair of braces and nothing nests.
json_field() {
    local body=$1 key=$2
    sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" <<<"$body" | head -n1
}

owner_batch() {
    local proxy=$1; shift
    local payload args
    payload=$(printf '"%s",' "$@"); payload="[${payload%,}]"
    args=(-sS --max-time "$TIMEOUT" -A 'ovpn-pin' -X POST
          -H 'Content-Type: application/json' --data-binary "$payload")
    if [ -n "$proxy" ]; then args+=(--proxy "$proxy"); else args+=(--noproxy '*'); fi
    curl "${args[@]}" \
        'http://ip-api.com/batch?fields=status,query,country,city,isp,org,as,asname' \
        2>/dev/null
}

owner_https_one() {
    local ip=$1 proxy=$2 args body name asn
    args=(-sS --max-time "$TIMEOUT" -A 'ovpn-pin')
    if [ -n "$proxy" ]; then args+=(--proxy "$proxy"); else args+=(--noproxy '*'); fi
    body=$(curl "${args[@]}" "https://ipwho.is/$ip" 2>/dev/null) || return 1

    # isp is the tidier of the two here - "M247 Europe SRL" against org's
    # "M247 LTD Frankfurt Infrastructure".
    name=$(json_field "$body" isp); [ -n "$name" ] || name=$(json_field "$body" org)
    [ -n "$name" ] || return 1
    asn=$(grep -oE '"asn"[[:space:]]*:[[:space:]]*[0-9]+' <<<"$body" | head -n1 | grep -oE '[0-9]+$')
    printf '%s\t%s\t%s\t%s' \
        "${asn:+AS$asn}" "$name" "$(json_field "$body" country)" "$(json_field "$body" city)"
}

# Fill the cache for whichever of these addresses it does not already know.
# Anything already answered - including the ones nobody would name, unless
# that was over a week ago - costs nothing.
lookup_owners() {
    local proxy=${OWNER_PROXY:-} want=() ip obj asn name cc city chunk=() n=0 batches i=0 learned=0 body row
    load_owners

    local cutoff
    cutoff=$(date -d '7 days ago' '+%Y-%m-%d' 2>/dev/null || printf '0000-00-00')
    for ip in "$@"; do
        [ -n "$ip" ] || continue
        if [ -z "${OWNER_NAME[$ip]:-}" ]; then
            want+=("$ip")
        elif [ "${OWNER_NAME[$ip]}" = '-' ] && [[ ${OWNER_WHEN[$ip]:-0} < $cutoff ]]; then
            want+=("$ip")
        fi
    done
    [ ${#want[@]} -gt 0 ] || return 0

    batches=$(( (${#want[@]} + 99) / 100 ))
    # Say what is happening while it happens: a hundred go in one request and
    # there is a deliberate wait between requests, so a few hundred new
    # addresses is half a minute in which nothing would otherwise be printed,
    # which reads as broken rather than busy. Not for one or two, which is what
    # a sweep asks about after each config.
    local chatty=0
    [ ${#want[@]} -gt 10 ] && chatty=1
    [ "$chatty" -eq 1 ] && info "asking about ${#want[@]} new address(es), $batches request(s)"

    while [ $i -lt ${#want[@]} ]; do
        chunk=("${want[@]:i:100}")
        [ "$chatty" -eq 1 ] && info "  request $(( i / 100 + 1 )) of $batches - ${#chunk[@]} addresses"

        body=$(owner_batch "$proxy" "${chunk[@]}")
        # Plain HTTP is the first thing a filtering middlebox eats. Try it
        # through a local proxy once before falling back to one at a time.
        if [ -z "$body" ] && [ -z "$proxy" ]; then
            proxy=$(find_proxy) && body=$(owner_batch "$proxy" "${chunk[@]}")
        fi

        if [ -n "$body" ]; then
            while IFS= read -r obj; do
                ip=$(json_field "$obj" query)
                [ -n "$ip" ] || continue
                name=$(json_field "$obj" asname)
                [ -n "$name" ] || name=$(json_field "$obj" isp)
                [ -n "$name" ] || name=$(json_field "$obj" org)
                [ -n "$name" ] || continue
                asn=$(json_field "$obj" as | grep -oE '^AS[0-9]+')
                cc=$(json_field "$obj" country)
                city=$(json_field "$obj" city)
                OWNER_NAME[$ip]=$name; OWNER_ASN[$ip]=$asn
                OWNER_CC[$ip]=$cc;     OWNER_CITY[$ip]=$city
                OWNER_WHEN[$ip]=$(today)
                learned=$((learned + 1))
            done < <(grep -o '{[^{}]*}' <<<"$body")
        else
            for ip in "${chunk[@]}"; do
                if row=$(owner_https_one "$ip" "$proxy"); then
                    IFS=$'\t' read -r asn name cc city <<<"$row"
                    OWNER_NAME[$ip]=$name; OWNER_ASN[$ip]=$asn
                    OWNER_CC[$ip]=$cc;     OWNER_CITY[$ip]=$city
                    OWNER_WHEN[$ip]=$(today)
                    learned=$((learned + 1))
                fi
            done
        fi

        # Whatever is still missing after both services had their turn is
        # written down as missing.
        for ip in "${chunk[@]}"; do
            if [ -z "${OWNER_NAME[$ip]:-}" ]; then
                OWNER_NAME[$ip]='-'; OWNER_ASN[$ip]=''
                OWNER_CC[$ip]='';    OWNER_CITY[$ip]=''
                OWNER_WHEN[$ip]=$(today)
            fi
        done
        OWNERS_DIRTY=1

        i=$((i + 100))
        # 15 batch requests a minute on the free tier, and being throttled
        # returns nothing rather than waiting.
        if [ $i -lt ${#want[@]} ]; then
            [ "$chatty" -eq 1 ] && info '  waiting 5s - the free tier allows 15 requests a minute'
            sleep 5
        fi
    done

    [ "$chatty" -eq 1 ] && info "$learned of ${#want[@]} came back with a name"
    save_owners
    return 0
}

# "M247 AS9009", or nothing at all when nobody would name it.
owner_of() {
    local ip=$1 n
    n=${OWNER_NAME[$ip]:-}
    [ -n "$n" ] && [ "$n" != '-' ] || return 1
    printf '%s%s' "$n" "${OWNER_ASN[$ip]:+ ${OWNER_ASN[$ip]}}"
}

owner_country() { printf '%s' "${OWNER_CC[$1]:-}"; }
owner_city()    { printf '%s' "${OWNER_CITY[$1]:-}"; }

# Anything that would be a nuisance in a filename, turned into dashes. Owner
# names arrive as "M247 AS9009" and "Cyberzone S.A." and neither belongs in a
# path as it stands.
name_tag() {
    local t=$1 max=${2:-28}
    t=$(sed 's/[^A-Za-z0-9]\+/-/g; s/^-//; s/-$//' <<<"$t")
    t=${t:0:$max}
    t=${t%-}
    printf '%s' "${t:-unknown}"
}

# 04.2s-de-fra..._1.2.3.4.ovpn -> de-fra..._1.2.3.4.ovpn. The success folder
# can itself be swept, so a name arriving from there already carries a time;
# without this the prefixes would stack up and every table keyed on the
# filename would treat the same config as a different one each round.
base_config_name() {
    sed -E 's/^[0-9]{1,3}(\.[0-9]+)?s-//' <<<"$1"
}


#----------------------------------------------------------------- cloudflare

UA_BROWSER='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

# Deliberately not through any proxy: the whole question is what the current
# default route - the tunnel - gets served, and a proxy would answer for
# itself instead. A browser user agent matters too; a bare or scripted one
# gets challenged on its own merits and would frame a clean exit as dirty.
cf_probe() {
    local url=$1 rc t
    t=$(tmp_dir)
    CF_STATUS=''; CF_VERDICT=''; CF_RAY=''; CF_ERR=''
    CF_BODY=$t/body.$$; CF_HDRS=$t/hdrs.$$

    CF_STATUS=$(curl -sS -L --noproxy '*' --max-time "$TIMEOUT" \
        -A "$UA_BROWSER" -H 'Accept: text/html,application/xhtml+xml,*/*' \
        -D "$CF_HDRS" -o "$CF_BODY" -w '%{http_code}' "$url" 2>"$t/err.$$")
    rc=$?

    if [ $rc -ne 0 ]; then
        CF_ERR=$(tr -d '\r' < "$t/err.$$" | sed 's/^curl: ([0-9]*) //' | tail -n1)
        [ -z "$CF_ERR" ] && CF_ERR="curl exit $rc"
        CF_VERDICT='unreachable'
        return 0
    fi

    local mitigated challenged=0
    mitigated=$(grep -i '^cf-mitigated:' "$CF_HDRS" 2>/dev/null | tail -n1 | tr -d '\r' | sed 's/^[^:]*:[[:space:]]*//')
    CF_RAY=$(grep -i '^cf-ray:' "$CF_HDRS" 2>/dev/null | tail -n1 | tr -d '\r' | sed 's/^[^:]*:[[:space:]]*//')

    # Cloudflare's block and challenge pages carry their own error numbers.
    # 1020 is a WAF rule, 1015 rate limiting, and the interstitials say so in
    # the title - all of which mean the address you arrived from, not the site.
    [ "$mitigated" = 'challenge' ] && challenged=1
    grep -qE 'Just a moment|Attention Required|Checking your browser|cf-challenge|__cf_chl|Error 10(20|15|09)' \
        "$CF_BODY" 2>/dev/null && challenged=1

    if   [ "$challenged" -eq 1 ];    then CF_VERDICT='challenged'
    elif [ "$CF_STATUS" = '403' ];   then CF_VERDICT='blocked'
    elif [ "$CF_STATUS" -ge 200 ] 2>/dev/null && [ "$CF_STATUS" -lt 400 ]; then CF_VERDICT='ok'
    else CF_VERDICT="http $CF_STATUS"
    fi
    return 0
}

# The hostnames a comma or space separated list names, one per line, skipping
# anything that is not one. Both scripts take --site the same way.
site_hosts() {
    local s h
    for s in $(printf '%s\n' "$@" | tr ',' '\n' | tr -s '[:space:]' '\n'); do
        [ -z "$s" ] && continue
        h=${s#*://}; h=${h%%/*}
        is_hostname "$h" || { warn "skipping '$s' - not a hostname"; continue; }
        printf '%s\n' "$h"
    done
}

# Judge the exit currently in use, in one line:
#
#     verdict <TAB> exit <TAB> good <TAB> bad <TAB> detail
#
# One line rather than a handful of globals because the caller wants this
# inside a $( ), and a subshell cannot hand variables back - a lesson this
# file has now learned twice.
cf_exit_verdict() {
    local sites=("$@") h exit_='' good=0 bad_=0 detail='' loc verdict

    cf_probe 'https://www.cloudflare.com/cdn-cgi/trace'
    if [ -n "$CF_ERR" ]; then
        printf 'unreachable\t\t0\t0\t%s' "$CF_ERR"
        return 0
    fi
    exit_=$(sed -n 's/^ip=//p' "$CF_BODY" | head -n1)
    loc=$(sed -n 's/^loc=//p' "$CF_BODY" | head -n1)
    [ -n "$loc" ] && exit_="$exit_ $loc"

    case $CF_VERDICT in ok) good=1 ;; *) bad_=1; detail='cloudflare.com itself' ;; esac

    for h in "${sites[@]}"; do
        [ -z "$h" ] && continue
        cf_probe "https://$h/"
        case $CF_VERDICT in
            ok)         good=$((good + 1)) ;;
            challenged) bad_=$((bad_ + 1)); detail="${detail:+$detail, }$h challenged" ;;
            blocked)    bad_=$((bad_ + 1)); detail="${detail:+$detail, }$h 403" ;;
            *)          detail="${detail:+$detail, }$h ${CF_VERDICT}" ;;
        esac
    done

    if   [ "$bad_" -eq 0 ] && [ "$good" -gt 0 ]; then verdict=clean
    elif [ "$good" -eq 0 ]; then verdict=dirty
    else verdict=partly
    fi
    [ -n "$detail" ] || detail="$good served"
    printf '%s\t%s\t%s\t%s\t%s' "$verdict" "$exit_" "$good" "$bad_" "$detail"
}


#-------------------------------------------------------------- pinned files

# The date this file was pinned, out of the header the pinner wrote.
pin_date() {
    sed -n 's/^# pinned by [^ ]* on \([0-9-]*\).*/\1/p' "$1" 2>/dev/null | head -n1
}

pin_age_days() {
    local d then now
    d=$(pin_date "$1")
    [ -n "$d" ] || return 1
    then=$(date -d "$d" +%s 2>/dev/null) || return 1
    now=$(date +%s)
    printf '%s' $(( (now - then) / 86400 ))
}

# host -> ip, as recorded in the header of a pinned file.
pin_hostmap() {
    sed -n 's/^#   \([^ ]*\) -> \([0-9.]*\)$/\1 \2/p' "$1" 2>/dev/null
}

# de-fra_tcp_146.70.160.237.ovpn -> de-fra_tcp
pin_base() {
    local n
    n=$(basename -- "$1"); n=${n%.ovpn}
    printf '%s' "${n%_*}"
}
