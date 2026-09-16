# Raw Google OSS Input Retention

Date: 2026-06-06

## Decision

Keep `GOOGLE-OSS-RAW-INPUTS.zip` attached to every tagged public Aura GitHub
Release that also publishes `THIRD-PARTY-NOTICES.md`.

Local release dry runs keep the archive in the checked `release/` directory
until it is attached to the matching GitHub Release.

## Rationale

- The archive contains generated release dependency inputs: `dependencies.json`,
  `third_party_license_metadata`, `third_party_licenses`, and `MANIFEST.json`.
- These files are derived from the release build and do not contain signing
  material, app secrets, local properties, or private user data.
- Keeping the raw inputs beside `THIRD-PARTY-NOTICES.md` lets release owners,
  downstream packagers, and users inspect notice drift without rerunning Gradle.
- GitHub Releases are the public distribution surface for the side-loaded APK,
  and release assets are the durable place for evidence that belongs with that
  exact APK.

## Enforced Behavior

The local release procedure:

- Builds `release/GOOGLE-OSS-RAW-INPUTS.zip` after generated notice checks.
- Includes the archive in `SHA256SUMS.txt`.
- Mentions the archive in `RELEASE_NOTES.md`.
- Attaches the archive to tagged GitHub Releases.

`tools/release_artifact_bundle_check.py` fails release bundles when the archive
is missing, empty, absent from `SHA256SUMS.txt`, absent from release notes, or
has a checksum mismatch.

## Change Control

Changing this decision requires updating all of these in one review:

- `tools/release_clean_clone_check.py`
- `tools/release_artifact_bundle_check.py`
- `docs/distribution/supply-chain.md`
- `docs/distribution/release-dry-run.md`
- `docs/distribution/release-signing.md`
- This file

If Aura later removes the public release attachment, the replacement evidence
location must still be available to users who download a tagged APK after
workflow artifacts expire.

## Sources

- GitHub workflow artifact retention:
  `https://docs.github.com/en/actions/tutorials/store-and-share-data`
- GitHub releases and release assets:
  `https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases`
- `actions/upload-artifact` retention behavior:
  `https://github.com/actions/upload-artifact`
- `softprops/action-gh-release` asset upload behavior:
  `https://github.com/softprops/action-gh-release`
