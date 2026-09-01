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
CFG=/etc/gost/config.yaml
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

say "secrets"
mkdir -p /etc/gost
newpw() { head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20; }
[ -s /etc/gost/tunnel.pw ] || newpw > /etc/gost/tunnel.pw
[ -s /etc/gost/api.pw ]    || newpw > /etc/gost/api.pw
chmod 600 /etc/gost/*.pw
PW=$(cat /etc/gost/tunnel.pw)
APW=$(cat /etc/gost/api.pw)

# keep whatever Surfshark credentials are already configured, if none given
if [ -z "$SSU" ] && [ -f "$CFG" ]; then
  SSU=$(sed -n '/name: exit/,/dialer:/p' "$CFG" | sed -n 's/.*username: "\(.*\)"/\1/p' | head -1)
  SSP=$(sed -n '/name: exit/,/dialer:/p' "$CFG" | sed -n 's/.*password: "\(.*\)"/\1/p' | head -1)
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
              auth: {username: "${SSU}", password: "${SSP}"}
            dialer:
              type: tls
              tls: {secure: false}

log:
  level: info
YAML
chmod 600 "$CFG"

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
systemctl enable --now gost >/dev/null 2>&1 || true
systemctl restart gost
sleep 2

say "result"
systemctl is-active gost
ss -lnt | grep -E '127.0.0.1:(1000[0-2]|18080)' | awk '{print "  listening " $4}'
cat <<SUMMARY

  Give these to the desktop app:

    domain          ${DOMAIN}
    tunnel password ${PW}
    api password    ${APW}

    single-IP  wss://${DOMAIN}/gw     (exits here)
    multi-IP   wss://${DOMAIN}/ex     (exits at a Surfshark node)
    bulk       wss://${DOMAIN}/gwb    (no multiplexing)
    control    https://${DOMAIN}/api/config/chains/exit-chain

SUMMARY
if [ "$SSU" = "CHANGEME" ]; then
  echo "  NOTE: no Surfshark credentials set - multi-IP mode will not work until"
  echo "        you re-run with:  $0 ${DOMAIN} <user> <pass>"
  echo
fi
