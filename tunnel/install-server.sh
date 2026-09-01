#!/usr/bin/env bash
# Relay tunnel - server installer.
#
#   ./install-server.sh <domain> [surfshark-user] [surfshark-pass]
#
# Idempotent: safe to re-run. Leaves any other nginx site alone - the block it
# writes carries its own server_name. Existing Surfshark credentials are kept
# if you re-run without passing them.
set -euo pipefail

DOMAIN="${1:-}"
SSU="${2:-}"
SSP="${3:-}"
[ -n "$DOMAIN" ] || { echo "usage: $0 <domain> [surfshark-user] [surfshark-pass]" >&2; exit 1; }

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
apt-get install -y -qq nginx certbot curl >/dev/null

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
mkdir -p /etc/gost /etc/sing-box
newpw() { head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20; }
[ -s /etc/gost/tunnel.pw ] || newpw > /etc/gost/tunnel.pw
[ -s /etc/gost/api.pw ]    || newpw > /etc/gost/api.pw
# Kept beside the passwords and generated once, so a phone that already holds
# a config keeps working when this is run again.
[ -s /etc/gost/vless.uuid ] || sing-box generate uuid > /etc/gost/vless.uuid
chmod 600 /etc/gost/*.pw /etc/gost/vless.uuid
PW=$(cat /etc/gost/tunnel.pw)
APW=$(cat /etc/gost/api.pw)
UUID=$(cat /etc/gost/vless.uuid)

# Keep whatever Surfshark credentials are already configured, if none given.
#
# Read with a non-greedy match, and not with sed. On a line carrying both
# keys, `s/.*username: "\(.*\)"/\1/` takes everything up to the last quote on
# that line - so the username came back as `user", password: "pass`, and
# writing it out again produced a line with two password keys and a config
# gost refused to parse. The block below writes one key per line for the
# same reason: a value that has to survive a round trip should not share a
# line with another one.
if [ -z "$SSU" ] && [ -f "$CFG" ]; then
  eval "$(python3 - "$CFG" <<'PY'
import re, shlex, sys
try:
    text = open(sys.argv[1]).read()
except OSError:
    text = ''
at = text.find('name: exit')
block = text[at:] if at >= 0 else ''
user = re.search(r'username:\s*"(.*?)"', block)
word = re.search(r'password:\s*"(.*?)"', block)
if user and word and user.group(1) != 'CHANGEME':
    print('SSU=%s; SSP=%s' % (shlex.quote(user.group(1)),
                              shlex.quote(word.group(1))))
PY
)"
fi
: "${SSU:=CHANGEME}" ; : "${SSP:=CHANGEME}"

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

say "nginx :443"
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
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name ${DOMAIN};
    ssl_certificate     /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;

NGX
  ws_block gw  10000     # single-IP mode  - exits at this server
  ws_block ex  10001     # multi-IP mode   - exits at the chosen Surfshark exit
  ws_block gwb 10002     # bulk, no multiplexing
  ws_block vl  10003     # the phone's door: VLESS over the same WebSocket
  cat <<NGX
    location /api/ {
        proxy_pass http://127.0.0.1:18080/api/;
        proxy_set_header Host \$host;
    }
    location / { return 404; }
}
NGX
} >> "$SITE"
nginx -t >/dev/null && systemctl reload nginx

say "gost"
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

log:
  level: info
YAML
chmod 600 "$CFG"

say "sing-box"
# No TLS here and none wanted: nginx has already terminated it, and behind
# that Cloudflare terminated the one the phone actually made. This end only
# has to be the far side of the WebSocket that arrives on /vl.
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

say "result"
for unit in gost sing-box; do printf '  %-9s %s
' "$unit" "$(systemctl is-active "$unit")"; done
ss -lnt | grep -E '127.0.0.1:(1000[0-3]|18080)' | awk '{print "  listening " $4}'
cat <<SUMMARY

  Give these to the desktop app:

    domain          ${DOMAIN}
    tunnel password ${PW}
    api password    ${APW}

    single-IP  wss://${DOMAIN}/gw     (exits here)
    multi-IP   wss://${DOMAIN}/ex     (exits at a Surfshark node)
    bulk       wss://${DOMAIN}/gwb    (no multiplexing)
    control    https://${DOMAIN}/api/config/chains/exit-chain

  And for phones - ovpn-mobile.py --tunnel wants these three:

    server ${DOMAIN}   path /vl   uuid ${UUID}

SUMMARY
if [ "$SSU" = "CHANGEME" ]; then
  echo "  NOTE: no Surfshark credentials set - multi-IP mode will not work until"
  echo "        you re-run with:  $0 ${DOMAIN} <user> <pass>"
  echo
fi
