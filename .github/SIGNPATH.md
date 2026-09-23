# SignPath Foundation release setup

The workflow is wired for SignPath but intentionally fails before publishing a
release until the SignPath project is approved and its GitHub settings exist.

## Before applying

1. Add an OSI-approved `LICENSE` file covering the whole project. SignPath
   Foundation will not approve a repository without one.
2. Keep the links to `CODE_SIGNING_POLICY.md` and `PRIVACY.md` on the repository
   home page. Enable multi-factor authentication for GitHub and SignPath.
3. Apply for a SignPath Foundation open-source subscription for
   `https://github.com/120hdd/ovpn-pin`.

## SignPath project

After approval, create an artifact configuration for the GitHub Actions
artifact produced by `desktop-release.yml`. The archive root is the contents of
the Relay distribution folder. Configure Authenticode signing for
`/Relay.exe`; do not sign the upstream `gost.exe` or bundled third-party DLLs as
Relay. Restrict PE metadata to product name `Relay`, and require matching file
and product versions.

Use a production signing policy with manual approval. The approver named in the
public code signing policy should hold the Approver role.

## GitHub repository settings

Create the Actions secret:

- `SIGNPATH_API_TOKEN`

Create these Actions variables with the values shown by the SignPath project:

- `SIGNPATH_ORGANIZATION_ID`
- `SIGNPATH_PROJECT_SLUG`
- `SIGNPATH_SIGNING_POLICY_SLUG`
- `SIGNPATH_ARTIFACT_CONFIGURATION_SLUG`

The API token only needs permission to submit signing requests. The workflow
uploads the unsigned directory to GitHub first, submits that artifact ID to
SignPath for origin verification, waits for manual approval, downloads the
signed result, validates the Authenticode signature, and only then creates the
release ZIP and GitHub Release.

Test with `workflow_dispatch` before creating the next `v*` tag. A missing
setting, rejected request, invalid signature, or unsigned executable stops the
workflow before publication.
