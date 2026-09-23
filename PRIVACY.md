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

To erase Relay's local data, quit it from the tray and delete its extracted
folder. No account or telemetry data is retained by the Relay project.
