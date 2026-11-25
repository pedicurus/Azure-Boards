# Releasing

Releases are built, signed and published by GitHub Actions (`.github/workflows/release.yml`)
whenever a version tag such as `v4.1` is pushed.

## One-time setup: signing secrets

The release build is signed with the upload key (alias `upload`). Add these as repository
secrets (Settings → Secrets and variables → Actions), or as secrets on the `release`
environment if you want release runs to need an approval:

| Secret | Value |
| :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | The keystore file encoded as base64: `base64 -w0 my-upload-key.jks` |
| `STORE_PASSWORD` | The keystore password |
| `KEY_PASSWORD` | The password of the `upload` key |

Don't have a key yet? Create one once and keep a backup somewhere safe (a password manager).
Losing it means you can no longer publish updates under the same app identity.

```bash
keytool -genkeypair -v -keystore my-upload-key.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Never commit the keystore: `*.jks` and `*.keystore` are in `.gitignore`.

## Cutting a release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName` (for example `4.1`).
2. Merge that change to `main`.
3. Create the tag `v` + `versionName` on `main`, in either of these ways:

   - **On the GitHub website (no tools needed):** open the repository's **Releases** page,
     click **Draft a new release**, then **Choose a tag**, type `v4.1` and pick
     **Create new tag: v4.1 on publish**. Leave the target as `main`, then click **Publish release**.
     The app files get attached to that release a few minutes later.
   - **From a terminal:**

     ```bash
     git tag v4.1 origin/main
     git push origin v4.1
     ```

The workflow then:

- stops if the tag doesn't match `versionName`, or if a signing secret is missing;
- runs the unit tests;
- builds the signed APK and AAB and checks the APK signature;
- publishes a GitHub Release with `AzureBoards-v4.1.apk`, `AzureBoards-v4.1.aab`,
  `SHA256SUMS.txt` and auto-generated release notes;
- keeps the R8 `mapping.txt` (for decoding crash stack traces) as a private workflow artifact for 90 days.

## Releasing from Google AI Studio

A push to `main` that raises `versionName` releases automatically, with no tag needed. If the
version already has a release, nothing happens. Paste this instruction into AI Studio:

> When I say "release", do this in the same commit:
> 1. In `app/build.gradle.kts`, increase `versionCode` by 1 and set `versionName` to the new
>    version (for example `4.0` → `4.1`). Do not change anything else in that file.
> 2. Overwrite `RELEASE_NOTES.md` in the repository root with short, user-facing notes for this
>    version: a `## What's new` list, then a `## Fixes` list. Plain language, one line per item,
>    based on the changes since the last release.
> 3. Use the commit message `release: v<versionName>`.
>
> Never change `versionName` in any other commit.

The workflow uses `RELEASE_NOTES.md` as the release notes, or GitHub's automatic notes if that file is missing.
