# Code signing policy

Free code signing provided by [SignPath.io](https://signpath.io/), certificate
by SignPath Foundation.

## Scope and provenance

Release builds are produced by the repository's GitHub Actions workflow from a
tagged commit. The unsigned artifact is uploaded by that workflow and submitted
to SignPath with GitHub origin verification. Every production signing request
requires manual approval. The signed product is `Relay.exe`; bundled upstream
open-source tools and libraries keep their own publisher identity and are not
signed as Relay.

The executable metadata identifies the product as `Relay` and takes its product
and file version from the release tag. Public releases are built fail-closed:
account credentials, sessions, local settings, and user-generated configuration
folders are rejected before signing.

## Team roles

- Committer and reviewer: [120hdd](https://github.com/120hdd)
- Signing-request approver: [120hdd](https://github.com/120hdd)

Repository and SignPath accounts used for these roles must have multi-factor
authentication enabled.

## Privacy and system changes

See the [privacy policy](PRIVACY.md). Relay announces the Windows system-proxy
change in its UI, records the previous configuration before changing it, and
restores it on disconnect, exit, failed connection, or the next launch after an
unclean shutdown.

## Uninstallation

Relay is portable and has no installer. Quit it from the tray first so Windows'
proxy settings are restored. Then delete the extracted Relay folder and any
shortcut created for it. This removes the application, its saved accounts,
sessions, logs, and settings. A separately configured tunnel server is not part
of the Windows installation and must be removed on that server separately.
