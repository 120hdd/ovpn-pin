#!/usr/bin/env bash
# Relay tunnel - server installer.
#
#   ./install-server.sh <ip-or-domain> [--password P] [--api-password A]
#                       [--port N] [--ss-port N] [--no-ss] [--slots N]
#
# One run sets up everything; the apps write this line themselves, with the
# passwords they already hold, so nothing has to be copied back into them.
#
# It ends by printing a key - relay://... - that carries the server, its
# passwords and its Shadowsocks settings. Pasted into either app it sets the
# whole tunnel up, which is how a server set up by somebody else, or by hand,
# reaches a phone without anybody typing a password into one.
#
# Shadowsocks is the second way in, beside the WebSocket that goes through
# Cloudflare: straight to this server's address, on two ports. The first
# (8388 unless --ss-port says otherwise) exits here; the one after it exits at
# whichever provider location the app has chosen, the same exit-chain the
# multi-IP WebSocket path uses. Straight to the address is the quicker of the
# two ways in when the address is reachable, and the only one that counts as
# domestic traffic when this server is inside Iran.
#
# It also keeps locations for other devices - an iPhone, a friend's laptop:
# anything with a V2Ray client and no Relay (--slots, 8 unless told). Each is
# a user on sing-box, reached by VLESS on /vl or VMess on /vm through the same
# nginx, and routed to its own gost chain, loc-<k>, which the app points at a
# provider location and saves. Slot 0 exits here. The users' UUIDs are derived
# from one id kept in /etc/relay/export.id, which the key carries, so the app
# can make any slot's link itself.
#
# An IP address is the ordinary case: no domain, no DNS, no CA. The server
# gets a self-signed certificate for that address and the apps pin it the
# first time they connect. A domain still works as before - a Let's Encrypt
# certificate, and Cloudflare in front if it is proxied there.
#
# The provider exit for server + exit is not set here. The app sends the
# location it was asked for, with that provider's own credential, through
# the API each time it connects - so no account ever goes into this line.
#
# --port moves the TLS door off 443, for a server where something else
# already answers there - an Xray, another site. The key carries the port, so
# the apps dial it; 443 is left out of the key, as it always was. Through
# Cloudflare it has to be one of the ports Cloudflare proxies (8443, 2053,
# 2083, 2087, 2096); straight to the address any port will do.
#
# Idempotent: safe to re-run. Leaves any other nginx site alone - the block it
# writes carries its own server_name. Passwords already on the server are
# kept unless new ones are passed.
set -euo pipefail

DOMAIN=""
NEWPW=""
NEWAPW=""
TLSPORT=443
SSPORT=8388
WITH_SS=1
SLOTS=8
while [ $# -gt 0 ]; do
  case "$1" in
    --password)     NEWPW="${2:-}"; shift 2 ;;
    --api-password) NEWAPW="${2:-}"; shift 2 ;;
    --port)         TLSPORT="${2:-}"; shift 2 ;;
    --ss-port)      SSPORT="${2:-}"; shift 2 ;;
    --no-ss)        WITH_SS=0; shift ;;
    --slots)        SLOTS="${2:-}"; shift 2 ;;
    -*) echo "unknown option: $1" >&2; exit 1 ;;
    *)  DOMAIN="$1"; shift ;;
  esac
done
[ -n "$DOMAIN" ] || { echo "usage: $0 <ip-or-domain> [--password P] [--api-password A] [--port N] [--ss-port N] [--no-ss] [--slots N]" >&2; exit 1; }
[[ "$TLSPORT" =~ ^[0-9]+$ ]] && [ "$TLSPORT" -ge 1 ] && [ "$TLSPORT" -le 65535 ] \
  || { echo "--port wants a number from 1 to 65535" >&2; exit 1; }
[[ "$SLOTS" =~ ^[0-9]+$ ]] && [ "$SLOTS" -le 32 ] \
  || { echo "--slots wants a number from 0 to 32" >&2; exit 1; }
[[ "$SSPORT" =~ ^[0-9]+$ ]] && [ "$SSPORT" -ge 1 ] && [ "$SSPORT" -le 65534 ] \
  || { echo "--ss-port wants a number from 1 to 65534" >&2; exit 1; }
# The exit port is always the one after, so a key never has to say both and
# a person reading a firewall rule can see they belong together.
SSXPORT=$((SSPORT + 1))
SSMETHOD=chacha20-ietf-poly1305

# An address rather than a name: no certificate authority will vouch for it
# without a name, so the certificate is this server's own.
IS_IP=0
if [[ "$DOMAIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then IS_IP=1; fi

GOST_VER=3.3.0
# The phone's half. gost carries the desktop, but no phone client speaks a
# proxy over WebSocket - sing-box's own `http` outbound has a path field and
# it is the path of an HTTP request, not an upgrade - so the same server also
# offers the one language they all do speak.
SB_VER=1.14.0
CFG=/etc/gost/config.yaml
SBCFG=/etc/sing-box/config.json
SITE="/etc/nginx/sites-available/relay-${DOMAIN}.conf"

say() { printf '\n=== %s\n' "$*"; }

say "packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
if [ "$IS_IP" = 1 ]; then
  apt-get install -y -qq nginx curl openssl >/dev/null
else
  apt-get install -y -qq nginx certbot curl >/dev/null
fi

say "gost ${GOST_VER}"
if [ "$(gost -V 2>/dev/null | grep -o "v${GOST_VER}" || true)" != "v${GOST_VER}" ]; then
  curl -fsSL -o /tmp/gost.tgz \
    "https://github.com/go-gost/gost/releases/download/v${GOST_VER}/gost_${GOST_VER}_linux_amd64.tar.gz"
  tar xzf /tmp/gost.tgz -C /tmp gost
  install -m755 /tmp/gost /usr/local/bin/gost
  rm -f /tmp/gost.tgz /tmp/gost
fi
gost -V

say "sing-box ${SB_VER}"
if ! sing-box version 2>/dev/null | head -1 | grep -q "${SB_VER}"; then
  curl -fsSL -o /tmp/sb.tgz \
    "https://github.com/SagerNet/sing-box/releases/download/v${SB_VER}/sing-box-${SB_VER}-linux-amd64.tar.gz"
  tar xzf /tmp/sb.tgz -C /tmp
  install -m755 "/tmp/sing-box-${SB_VER}-linux-amd64/sing-box" /usr/local/bin/sing-box
  rm -rf /tmp/sb.tgz "/tmp/sing-box-${SB_VER}-linux-amd64"
fi
sing-box version | head -1

say "secrets"
mkdir -p /etc/gost /etc/sing-box /etc/relay
newpw() { head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20; }
# The app's own passwords when it sent them, so it already knows them.
if [ -n "$NEWPW" ];  then printf '%s' "$NEWPW"  > /etc/gost/tunnel.pw; fi
if [ -n "$NEWAPW" ]; then printf '%s' "$NEWAPW" > /etc/gost/api.pw; fi
[ -s /etc/gost/tunnel.pw ] || newpw > /etc/gost/tunnel.pw
[ -s /etc/gost/api.pw ]    || newpw > /etc/gost/api.pw
# Kept beside the passwords and generated once, so a phone that already holds
# a config keeps working when this is run again.
[ -s /etc/gost/vless.uuid ] || sing-box generate uuid > /etc/gost/vless.uuid
# Shadowsocks has one key for everybody who connects, so it is its own and not
# the tunnel password: a key handed to a phone should not also be the
# password of the WebSocket paths.
[ -s /etc/gost/ss.pw ] || newpw > /etc/gost/ss.pw
# The id the exported locations' UUIDs are derived from. Kept, like the
# rest, so links already handed out keep working when this runs again.
[ -s /etc/relay/export.id ] || newpw > /etc/relay/export.id
chmod 600 /etc/gost/*.pw /etc/gost/vless.uuid /etc/relay/export.id
PW=$(cat /etc/gost/tunnel.pw)
XID=$(cat /etc/relay/export.id)
if [ "$SLOTS" -gt 0 ] && ! command -v python3 >/dev/null 2>&1; then
  # The UUIDs are uuid5s, which is one line of Python and no line of bash.
  echo "  python3 is missing, so no locations for other devices this time"
  SLOTS=0
fi
APW=$(cat /etc/gost/api.pw)
UUID=$(cat /etc/gost/vless.uuid)
SSPW=$(cat /etc/gost/ss.pw)

# The exit node's account is the app's to send (see the top). Until it does,
# multi-IP mode points at a placeholder that refuses, which is the honest
# thing for it to do with no account.
SSU=CHANGEME ; SSP=CHANGEME

if [ "$IS_IP" = 1 ]; then
  say "certificate (self-signed, for ${DOMAIN})"
  # Ten years, because nothing renews it: the apps trust this exact
  # certificate, not a CA, so a new one would only make them refuse.
  CERT=/etc/relay/cert.pem
  KEY=/etc/relay/key.pem
  mkdir -p /etc/relay
  if [ ! -s "$CERT" ] || ! openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | grep -q "IP Address:${DOMAIN}\$"; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
      -days 3650 -subj "/CN=${DOMAIN}" \
      -addext "subjectAltName=IP:${DOMAIN}" \
      -addext "extendedKeyUsage=serverAuth" \
      -keyout "$KEY" -out "$CERT" 2>/dev/null
    chmod 600 "$KEY"
  fi
  PIN=$(openssl x509 -in "$CERT" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')
  : > "$SITE"
else
  say "nginx :80 (needed for the certificate)"
  mkdir -p /var/www/certbot
  cat > "$SITE" <<NGX
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 404; }
}
NGX
  ln -sf "$SITE" /etc/nginx/sites-enabled/
  nginx -t >/dev/null && systemctl reload nginx

  say "certificate"
  if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    certbot certonly --webroot -w /var/www/certbot -d "${DOMAIN}" \
      --non-interactive --agree-tos --register-unsafely-without-email
  fi
  CERT="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"
  KEY="/etc/letsencrypt/live/${DOMAIN}/privkey.pem"
  PIN=""
fi

say "nginx :${TLSPORT}"
ws_block() {  # $1 = path, $2 = upstream port
cat <<NGX
    location /$1 {
        proxy_pass http://127.0.0.1:$2;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
NGX
}
{
  cat <<NGX
server {
    listen ${TLSPORT} ssl;
    listen [::]:${TLSPORT} ssl;
    server_name ${DOMAIN};
    ssl_certificate     ${CERT};
    ssl_certificate_key ${KEY};

NGX
  ws_block gw  10000     # single-IP mode  - exits at this server
  ws_block ex  10001     # multi-IP mode   - exits at the chosen Surfshark exit
  ws_block gwb 10002     # bulk, no multiplexing
  ws_block vl  10003     # the phone's door: VLESS over the same WebSocket
  if [ "$SLOTS" -gt 0 ]; then
    ws_block vm 10004    # other devices: VMess, beside the VLESS above
  fi
  cat <<NGX
    location /api/ {
        proxy_pass http://127.0.0.1:18080/api/;
        proxy_set_header Host \$host;
    }
    location / { return 404; }
}
NGX
} >> "$SITE"
ln -sf "$SITE" /etc/nginx/sites-enabled/
nginx -t >/dev/null && { systemctl enable --now nginx >/dev/null 2>&1 || true; systemctl reload nginx; }

say "gost"
# Not behind nginx: Shadowsocks is its own encryption on a bare TCP port, and
# going straight to the address is the point of it. One key for both ports.
SS_SERVICES=""
if [ "$WITH_SS" = 1 ]; then
  SS_SERVICES=$(cat <<YAML

  - name: ss-single
    addr: ":${SSPORT}"
    handler: {type: ss, auth: {username: ${SSMETHOD}, password: ${SSPW}}}
    listener: {type: tcp}

  - name: ss-multi
    addr: ":${SSXPORT}"
    handler: {type: ss, chain: exit-chain, auth: {username: ${SSMETHOD}, password: ${SSPW}}}
    listener: {type: tcp}
YAML
)
fi
# One gost service and chain per exported location: sing-box hands each
# slot's user to its service, and the app points the chain at a provider
# exit and saves the file. Until it does, each refuses, like exit-chain.
LOC_SERVICES=""
LOC_CHAINS=""
for k in $(seq 1 "$SLOTS"); do
  LOC_SERVICES="${LOC_SERVICES}
  - name: loc-${k}
    addr: \"127.0.0.1:$((10100 + k))\"
    handler: {type: http, chain: loc-${k}, auth: {username: relay, password: ${PW}}}
    listener: {type: tcp}
"
  LOC_CHAINS="${LOC_CHAINS}
  - name: loc-${k}
    hops:
      - name: exit
        nodes:
          - name: current
            addr: 146.70.194.221:443
            connector:
              type: http
              auth:
                username: \"${SSU}\"
                password: \"${SSP}\"
            dialer:
              type: tls
              tls: {secure: false}
"
done
cat > "$CFG" <<YAML
api:
  addr: "127.0.0.1:18080"
  pathPrefix: /api
  accesslog: false
  auth:
    username: relay
    password: ${APW}

services:
  - name: single
    addr: "127.0.0.1:10000"
    handler: {type: http, auth: {username: relay, password: ${PW}}}
    listener: {type: mws, metadata: {path: /gw}}

  - name: multi
    addr: "127.0.0.1:10001"
    handler: {type: http, chain: exit-chain, auth: {username: relay, password: ${PW}}}
    listener: {type: mws, metadata: {path: /ex}}

  - name: bulk
    addr: "127.0.0.1:10002"
    handler: {type: http, auth: {username: relay, password: ${PW}}}
    listener: {type: ws, metadata: {path: /gwb}}
${SS_SERVICES}
${LOC_SERVICES}

chains:
  - name: exit-chain
    hops:
      - name: exit
        nodes:
          - name: current
            addr: 146.70.194.221:443
            connector:
              type: http
              auth:
                username: "${SSU}"
                password: "${SSP}"
            dialer:
              type: tls
              tls: {secure: false}
${LOC_CHAINS}

log:
  level: info
YAML
chmod 600 "$CFG"

say "sing-box"
# No TLS here and none wanted: nginx has already terminated it, and behind
# that Cloudflare terminated the one the phone actually made. This end only
# has to be the far side of the WebSocket that arrives on /vl.
if [ "$SLOTS" -gt 0 ]; then
  # Written by Python: one user per slot on each door, each routed to its
  # own gost chain, and that is more JSON than a heredoc should be trusted
  # with.
  UUID="$UUID" XID="$XID" N="$SLOTS" PW="$PW" python3 - > "$SBCFG" <<'PY'
import json, os, uuid
e = os.environ
n = int(e['N'])

def users():
    return [{'name': f'slot-{k}',
             'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL,
                                    f"relay-export:{e['XID']}:{k}"))}
            for k in range(n + 1)]

print(json.dumps({
    'log': {'level': 'warn', 'timestamp': True},
    'inbounds': [
        {'type': 'vless', 'tag': 'phone', 'listen': '127.0.0.1',
         'listen_port': 10003,
         'users': [{'name': 'relay', 'uuid': e['UUID']}] + users(),
         'transport': {'type': 'ws', 'path': '/vl'}},
        {'type': 'vmess', 'tag': 'others', 'listen': '127.0.0.1',
         'listen_port': 10004,
         'users': [dict(u, alterId=0) for u in users()],
         'transport': {'type': 'ws', 'path': '/vm'}},
    ],
    'outbounds': [{'type': 'direct', 'tag': 'out'}] + [
        {'type': 'http', 'tag': f'loc-{k}', 'server': '127.0.0.1',
         'server_port': 10100 + k, 'username': 'relay', 'password': e['PW']}
        for k in range(1, n + 1)],
    'route': {
        'rules': [{'auth_user': [f'slot-{k}'], 'outbound': f'loc-{k}'}
                  for k in range(1, n + 1)],
        'final': 'out'},
}, indent=2))
PY
else
cat > "$SBCFG" <<JSON
{
  "log": { "level": "warn", "timestamp": true },
  "inbounds": [
    {
      "type": "vless",
      "tag": "phone",
      "listen": "127.0.0.1",
      "listen_port": 10003,
      "users": [ { "uuid": "${UUID}", "name": "relay" } ],
      "transport": { "type": "ws", "path": "/vl" }
    }
  ],
  "outbounds": [ { "type": "direct", "tag": "out" } ]
}
JSON
fi
chmod 600 "$SBCFG"
sing-box check -c "$SBCFG"

cat > /etc/systemd/system/sing-box.service <<UNIT
[Unit]
Description=Relay tunnel, the phone's half (sing-box)
After=network.target
[Service]
ExecStart=/usr/local/bin/sing-box run -c ${SBCFG}
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/gost.service <<UNIT
[Unit]
Description=Relay tunnel (gost)
After=network.target
[Service]
ExecStart=/usr/local/bin/gost -C ${CFG}
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
for unit in gost sing-box; do
  systemctl enable --now "$unit" >/dev/null 2>&1 || true
  systemctl restart "$unit"
done
sleep 2

if command -v ufw >/dev/null 2>&1 \
    && ufw status 2>/dev/null | grep -q '^Status: active'; then
  # Only when ufw is already guarding the box: switching a firewall on is not
  # this script's decision, but leaving its own ports shut behind one is.
  say "firewall"
  OPENED="${TLSPORT}/tcp"
  ufw allow "${TLSPORT}/tcp" >/dev/null
  if [ "$WITH_SS" = 1 ]; then
    ufw allow "${SSPORT}/tcp" >/dev/null
    ufw allow "${SSXPORT}/tcp" >/dev/null
    OPENED="${OPENED}, ${SSPORT}/tcp and ${SSXPORT}/tcp"
  fi
  echo "  opened ${OPENED}"
fi

# The key: everything an app needs, in one line to paste. base64url of a JSON
# object so it survives a chat message, a QR code and a terminal that wraps.
# Kept on the server, so it can be shown again without running this again:
#   cat /etc/relay/key
mkdir -p /etc/relay
SSJSON=""
if [ "$WITH_SS" = 1 ]; then
  SSJSON=",\"ss\":{\"method\":\"${SSMETHOD}\",\"password\":\"${SSPW}\",\"port\":${SSPORT},\"exitPort\":${SSXPORT}}"
fi
if command -v python3 >/dev/null 2>&1; then
  KEY=$(HOST="$DOMAIN" PIN="$PIN" PW="$PW" APW="$APW" SS="$WITH_SS" \
        SSM="$SSMETHOD" SSP="$SSPW" SSPORT="$SSPORT" SSX="$SSXPORT" \
        XID="$XID" SLOTS="$SLOTS" TLSPORT="$TLSPORT" python3 - <<'PY'
import base64, json, os
e = os.environ
key = {'v': 1, 'host': e['HOST'], 'pin': e['PIN'], 'pw': e['PW'], 'apw': e['APW']}
if int(e['TLSPORT']) != 443:
    key['port'] = int(e['TLSPORT'])
if e['SS'] == '1':
    key['ss'] = {'method': e['SSM'], 'password': e['SSP'],
                 'port': int(e['SSPORT']), 'exitPort': int(e['SSX'])}
if int(e['SLOTS']) > 0:
    key['x'] = {'id': e['XID'], 'n': int(e['SLOTS'])}
raw = json.dumps(key, separators=(',', ':')).encode()
print('relay://' + base64.urlsafe_b64encode(raw).decode().rstrip('='))
PY
)
else
  # Every value here is an address, hex, a number or a generated password,
  # none of which needs escaping in JSON.
  PORTJSON=""
  if [ "$TLSPORT" != 443 ]; then PORTJSON=",\"port\":${TLSPORT}"; fi
  RAW="{\"v\":1,\"host\":\"${DOMAIN}\",\"pin\":\"${PIN}\",\"pw\":\"${PW}\",\"apw\":\"${APW}\"${PORTJSON}${SSJSON}}"
  KEY="relay://$(printf '%s' "$RAW" | base64 -w0 | tr '+/' '-_' | tr -d '=')"
fi
printf '%s\n' "$KEY" > /etc/relay/key
chmod 600 /etc/relay/key

say "result"
for unit in gost sing-box; do printf '  %-9s %s
' "$unit" "$(systemctl is-active "$unit")"; done
ss -lnt | grep -E "127.0.0.1:(1000[0-4]|101[0-3][0-9]|18080)|:(${TLSPORT}|${SSPORT}|${SSXPORT}) " | awk '{print "  listening " $4}'
cat <<SUMMARY

  Done. The app that gave you this command already has what it needs:
  press Test in its tunnel settings.

    server          ${DOMAIN}
    tunnel password ${PW}
    api password    ${APW}
SUMMARY
if [ -n "$PIN" ]; then
  echo "    certificate     sha256 ${PIN}"
  echo "                    (the apps pin this the first time they connect)"
fi
AT="${DOMAIN}"
if [ "$TLSPORT" != 443 ]; then AT="${DOMAIN}:${TLSPORT}"; fi
cat <<SUMMARY

    single-IP  wss://${AT}/gw     (exits here)
    multi-IP   wss://${AT}/ex     (exits at the location the app picks)
    control    https://${AT}/api/config/chains/exit-chain
SUMMARY
if [ "$WITH_SS" = 1 ]; then
  cat <<SUMMARY
    shadowsocks ${DOMAIN}:${SSPORT}  (exits here)
                ${DOMAIN}:${SSXPORT}  (exits at the location the app picks)
                ${SSMETHOD}
SUMMARY
fi
if [ "$SLOTS" -gt 0 ]; then
  cat <<SUMMARY
    other devices  ${SLOTS} locations, VLESS on /vl and VMess on /vm
                   (the app picks them: Settings, Share with other devices -
                   running this again resets them, so share again after)
SUMMARY
fi
cat <<SUMMARY

  For phones - ovpn-mobile.py --tunnel wants:  server ${DOMAIN}  path /vl  uuid ${UUID}

  The key for either app - Settings, Your own tunnel, Paste a key.
  It holds the passwords above: treat it like one. Shown again by
  cat /etc/relay/key

${KEY}

SUMMARY
