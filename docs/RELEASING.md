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

> **Version numbers**
> - `versionCode` is a build counter that Google Play publishing manages. Never edit it by hand
>   and never derive it from `versionName`.
> - `versionName` is the version people see, written as `MAJOR.MINOR.PATCH`. Change it only during
>   a release. The `versionName` currently in `app/build.gradle.kts` is the last released version
>   (treat `5.0` as `5.0.0`); you don't need git history. Never set it lower than that.
>   - PATCH (+0.0.1): bug fixes, UI polish, non-breaking performance tweaks (5.0.0 → 5.0.1).
>   - MINOR (+0.1.0): new features, backward-compatible additions. Reset PATCH to 0 (5.0.1 → 5.1.0).
>   - MAJOR (+1.0.0): breaking changes, major redesigns, workflow rewrites. Reset MINOR and PATCH
>     to 0 (5.1.0 → 6.0.0).
>
> **When I say "release":**
> 1. Run all tests. If any fail, stop and tell me.
> 2. Check that the release build has R8 enabled (`isMinifyEnabled = true`) and is not debuggable.
>    If not, stop and tell me; don't fix it inside the release.
> 3. Review the changes made since the current `versionName` was set, then propose the bump
>    (PATCH / MINOR / MAJOR) and the new version with a one-line reason. Wait for my confirmation.
> 4. In `app/build.gradle.kts`, set `versionName` to the confirmed version. Change nothing else in that file.
> 5. Overwrite `RELEASE_NOTES.md` in the repository root with short, user-facing notes for this
>    version, using these sections and leaving out any that are empty: `## New`, `## Improved`,
>    `## Fixed`, `## Breaking changes`, `## Security`. Plain language, one line per item.
> 6. Change only these two files. Remind me to sync both to GitHub together; no git commands or
>    commit message are needed.
> 7. In your reply, show me the full contents of `RELEASE_NOTES.md`.
>
> Never build, sign or upload the APK/AAB yourself, and never commit them or the keystore:
> GitHub builds and signs the release.

The commit message doesn't matter: the pipeline only checks whether `versionName` changed.
The workflow uses `RELEASE_NOTES.md` as the release notes, or GitHub's automatic notes if that file is missing.

`versionCode` is owned by AI Studio's Google Play publishing, which raises it each time it
publishes. Google Play only accepts a `versionCode` higher than the last one it received.
The GitHub release pipeline only looks at `versionName`, so a Play publish never starts a
GitHub release by accident.
