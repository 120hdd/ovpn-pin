# Privacy policy

Relay has no analytics, advertising, crash-reporting service, or background
telemetry. It does not transfer information to networked systems unless the
user requests or enables a feature that needs that transfer.

When the user connects, Relay sends network traffic and the chosen provider's
service credentials to that provider's proxy endpoints. If the user configures
their own tunnel, traffic and tunnel credentials are also sent to that server
and its configured CDN. Connection checks may contact Cloudflare's trace and
DNS-over-HTTPS services. Pinning, server refresh, address-owner lookup, and site
tests contact the providers and lookup/test services named by those actions.

Account records are protected for the current Windows user. Provider proxy
credentials are also written locally beside the app in credential files with
Windows access controls restricted to that user. Logs, settings, sessions, and
generated configurations remain inside the extracted application folder. Some
generated mobile or inline-auth configurations can contain credentials in
clear text; the app labels this behavior and does not publish those files.

Relay changes the Windows system proxy only when the user enables routing all
of Windows. It saves the previous configuration before making the change and
restores it on disconnect, exit, failed connection, or recovery after an
unclean shutdown.

When the user chooses Tunnel instead of the proxy setting, Relay asks Windows
for administrator rights and runs sing-box to add a temporary network adapter
and its routes, so that every program's traffic reaches the same connection.
DNS lookups are then sent over HTTPS to Cloudflare's 1.1.1.1 through that
connection. The adapter, its routes and its firewall filters are removed on
disconnect, exit, failed connection, or when the connection's proxy process
stops.

Split tunnelling is off unless the user turns it on. When it is on, the
connections it lists (or, with "Only these", the ones it does not list) leave
by the user's own internet line instead of the connection, and are visible to
that line like any traffic without a VPN. With "Except these", the names it
sends that way are also looked up from the line's own DNS server. The Iran
lists it can use are files shipped inside the app; nothing is downloaded to
use them.

Sharing configs with other devices is done only when the user asks for it.
For each location chosen, Relay sends the user's own server the address of a
provider exit and that provider's account credentials, through the server's
API, and asks the server to save them to its configuration file so the
location keeps working after a restart. The credentials stay on the user's
server until the location is pointed elsewhere or the installer is run
again. The links Relay then shows give whoever holds them use of that
server.

To erase Relay's local data, quit it from the tray and delete its extracted
folder. No account or telemetry data is retained by the Relay project.
