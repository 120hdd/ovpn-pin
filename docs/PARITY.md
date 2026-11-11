# Windows and Android, feature by feature

The two apps share no code: Windows is Python with an HTML window (`app/`,
`core/`), Android is Kotlin with a Go core (`android/`, `mobile/core`). So a
feature lands twice, in two pull requests, and this table is how neither app
falls behind without anybody noticing. Every pull request that adds or
changes a feature updates its row.

`yes` has it · `part` has some of it · `no` does not

| Feature | Windows | Android | Notes |
|---|---|---|---|
| Provider accounts (Surfshark, Windscribe) | yes | yes | |
| Fetch servers and pin them to real addresses | yes | yes | |
| Automatic fetch and pin right after sign-in | yes | yes | |
| Pin from the account cards | part | yes | Windows: on the provider cards only |
| Race the provider's exits, first to answer wins | yes | yes | |
| Own server, and server + exit | yes | yes | by IP (self-signed, pinned on first Test) or domain; one install command from Settings with the app's own passwords; server + exit sends the picked location and its provider's account |
| Every program through it, not only proxy-aware ones (TUN) | yes | yes | Windows: Settings → Connection → Tunnel, sing-box + UAC; Android: always, VpnService |
| Own server set up from one pasted key (relay://…) | yes | yes | printed by install-server.sh, kept in /etc/relay/key; carries the pin, both passwords, Shadowsocks and the TLS port when it is not 443 (--port) |
| Shadowsocks straight to your own server's address | yes | yes | the server's port exits there, the next port goes through the exit-chain (server + exit); Windows via gost, Android in the Go core |
| Way out chosen from a dropdown, not a three-way strip | yes | yes | three labels side by side did not fit narrow phones |
| Split tunnel: all except a list, or only a list | yes | yes | sites, addresses and ranges; Windows: sing-box rules with the adapter, the worker with the proxy setting; Android: the Go core, UDP included for what goes around |
| Per-app routing | yes | yes | Windows by exe name (with the proxy setting, only programs that use it); Android by package, through VpnService |
| Iran preset: sites, address ranges, apps | yes | yes | lists/ - ranges from RIPE NCC via lists/update.py, the rest by hand; apps on Android only |
| Configs for other devices (VLESS / VMess links, QR, several locations) | yes | yes | your own server only, installer from this version (--slots); each location pinned to the exit that uploads best, saved on the server |
| Share this connection on Wi-Fi / hotspot (HTTP + SOCKS5 proxy for other devices) | yes | yes | private addresses only; Windows adds a Private-profile firewall rule (UAC), Android serves it from the Go core while connected |
| Locations grouped by continent, Fastest available / Test all removed | yes | yes | continent comes from the catalogue (countries.py / countries.go) |
| Connected country's flag dimmed behind the home screen | yes | yes | |
| Three themes: Graphite, White, Black | yes | yes | older theme names map onto these |
| Traffic meter, counted as bytes flow | yes | yes | |
| What-is-using-the-connection list | yes | yes | |
| Welcome page when nothing is set up | yes | yes | |
| No way in without an account or server | part | yes | Windows opens Settings |
| First-run intro slides, logo drawn in | yes | yes | |
| Orb logo (disc, open ring, bead) | yes | yes | |
| Name in the header, no always-on PRIVATE badge | yes | yes | |
| DISCONNECTED in red | yes | yes | |
| Traffic-light orb colours, with a setting | yes | yes | |
| Click the orb to connect, hold to reconnect | yes | yes | |
| PRESS TO CONNECT label | yes | yes | |
| Colour themes, including a light one | yes | yes | nine on both |
| (i) buttons instead of inline help | yes | yes | |
| Long addresses shrink to fit | yes | yes | |
| Live connection timer | yes | yes | Android: in the notification too |
| Version in About | no | yes | |
| Tray / quick-settings tile, one-press connect | part | yes | Windows: tray menu |
| Accounts screen, one card per provider, one account each | yes | yes | |
| Quick timing: one exit per country, on its own every 6 h | yes | yes | |
| Untimed countries listed apart under Fastest first | yes | yes | |
| Failed exit: red mark, reason on hover / tap | yes | yes | |
| Connection-type strip only when more than one works | yes | yes | |
| City rows: time on the right, star and test of their own | yes | yes | |
| Exit list as cards: time and place under the name, badges line, cities inside | yes | yes | |
| Sort chips with words, source chip, Fastest available card | yes | yes | |
| Narrow screens: rows fold to two lines instead of cutting text | yes | yes | Android also for a large font |
| Wide screens: content in a centred column | yes | yes | Windows from 720px, Android tablets |
| Window size, single instance | yes | n/a | |

## Releases

Each app has its own tags, so releasing one never releases the other:

- `desktop-v0.3.0` → `.github/workflows/desktop-release.yml` → `Relay-windows-x64-….zip`
- `android-v0.2.0` → `.github/workflows/release.yml` → `relay-android-v0.2.0.apk`;
  the tag must match `versionName` in `android/app/build.gradle.kts`

## Working on either app

- Branch from `main`, one change per branch: `desktop-<topic>` or
  `android-<topic>` (a dash, not a slash: a branch named `android` already
  exists, and git cannot have both `android` and `android/…`).
- Open a pull request into `main`. Its app's workflow builds and tests it;
  Windows also runs the built `Relay.exe --selftest`.
- Try it on a real window or phone before merging.
