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
| Automatic fetch and pin right after sign-in | no | yes | Windows: a button in Settings |
| Pin from the account cards | part | yes | Windows: on the provider cards only |
| Race the provider's exits, first to answer wins | yes | yes | |
| Own server, and server + exit | yes | yes | |
| Traffic meter, counted as bytes flow | yes | yes | |
| What-is-using-the-connection list | yes | yes | |
| Welcome page when nothing is set up | no | yes | |
| No way in without an account or server | part | yes | Windows opens Settings |
| First-run intro slides, logo drawn in | no | yes | |
| Orb logo (disc, open ring, bead) | no | yes | Windows: the old R |
| Name in the header, no always-on PRIVATE badge | no | yes | |
| DISCONNECTED in red | no | yes | |
| Traffic-light orb colours, with a setting | no | yes | |
| Click the orb to connect, hold to reconnect | no | yes | |
| PRESS TO CONNECT label | no | yes | |
| Colour themes, including a light one | no | yes | Android: nine |
| (i) buttons instead of inline help | no | yes | |
| Long addresses shrink to fit | no | yes | |
| Live connection timer | no | yes | Android: in the notification |
| Version in About | no | yes | |
| Tray / quick-settings tile, one-press connect | part | yes | Windows: tray menu |
| Accounts screen, one card per provider, one account each | yes | yes | |
| Quick timing: one exit per country, on its own every 6 h | yes | yes | |
| Untimed countries listed apart under Fastest first | yes | yes | |
| Failed exit: red mark, reason on hover / tap | yes | yes | |
| Connection-type strip only when more than one works | yes | yes | |
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
