# Azure Boards Android: architecture review and improvement plan

> **Status (2026-11-24):**
> - Phase 1 (security) is done and merged (PR #11), along with the CI and Dependabot parts of Phase 4.
> - Phase 2 Part A (performance) is implemented on branch `claude/cool-cannon-c5l21m`.
> - Phase 2 Part B and Phase 3 are not started.
>
> See the notes under each phase.

## Context
You asked for an architecture review of the app (Kotlin, Jetpack Compose, about 26k lines) and a plan to make it faster, more effective and more secure. Three parallel reviews (security, performance, architecture) went through the whole codebase, and I checked the key findings in the code myself.

**Overall:** the foundations are sound:
- Saved tokens and the offline cache are encrypted.
- User input is checked before it's used.
- There's no secret logging.
- Moshi generates its JSON adapters at build time.
- Lists use stable keys, and lifecycle-aware state is used throughout.

The main problems:
1. **Security:** one real hole. Cached data can be seen without a valid token.
2. **Performance:** the app downloads far more than it needs. That means about 30 requests per project at every start, every work item in the organisation, and notification polling every 3 minutes even in the background. It also does disk and encryption work before the first frame appears.
3. **Effectiveness:** the parts of the app are wired together through global callbacks and shared lists that several parts write to, with no typed errors, no CI and thin tests. That makes changes risky.

The work is split into four phases, in priority order. Each phase can ship on its own.

---

## Phase 1: Security fixes (size S–M, do first): ✅ done

Notes on what was implemented:
- 1.1: A blank token on the Connection screen now means "use the saved token". The cache is only restored when the token matches the one held for that org. Cache files of orgs that aren't saved are deleted on the next app start.
- 1.4: A rejected token is flagged per org. Sync skips that org, and the background worker posts one "Sign in again" notification. The flag clears automatically on the next successful sign-in. The board screen now shows a dismissible error banner.
- 1.5: R8 has a CI check (`minifyReleaseWithR8`), but a signed release build still needs a manual smoke test.
- 1.8: OkHttp was updated to 4.12.0. Compose and lifecycle are left to Dependabot PRs, so each upgrade is compiled by CI separately.

| # | Fix | Where | How |
|---|---|---|---|
| 1.1 | **Don't show cached data before the token is checked** (High) | `ui/viewmodel/delegates/WorkItemStreamDelegate.kt:377-397, 495-501` | Restore the cache and set `Connected` only if (a) the org is a saved account and the PAT entered matches the stored one, or (b) the first API call succeeds. On failure, set `ConnectionStatus.Error` and clear `allWorkItems`. Delete cache files for sessions that weren't remembered, using the existing per-org cache delete that `removeOrganizationAccount` calls. |
| 1.2 | **Never pre-fill or reveal a stored PAT** | `ui/state/UiStates.kt:23`, `ui/navigation/AzureNavigation.kt:78`, `ui/screens/ConnectionScreen.kt:104,242,401-452`, `ui/screens/AzureBoardsSettingsDialog.kt:326`, `ConnectionViewModel.getInitialPat` | Remove `initialPat`. Show a "Saved token ••••" placeholder; an empty field means keep the current token. Add `FLAG_SECURE` (set in a `DisposableEffect`) on the Connection and Settings screens. |
| 1.3 | **Keep notification content off the lock screen** | `notifications/NotificationHelper.kt:88-133`, `data/repository/AzureAccountRepository.kt:276-285` | Use `setVisibility(VISIBILITY_PRIVATE)` with a generic `setPublicVersion(...)`. Call `NotificationManagerCompat.cancelAll()` on disconnect. |
| 1.4 | **Stop retrying revoked or expired tokens** | `data/repository/AzureNotificationsRepository.kt:405-440`, `notifications/AzureNotificationSyncWorker.kt` | Pass 401/403 back up as an auth error. Flag the account as needing re-login, skip it in sync, and show a banner. Uses the error type from 3.3. |
| 1.5 | **Harden the release build** | `app/build.gradle.kts`, `AndroidManifest.xml`, new `res/xml/network_security_config.xml` | Set `isMinifyEnabled = true` and `isShrinkResources = true`, keeping the R8 keep rules for Moshi codegen, Retrofit and serializable routes (these mostly ship with the libraries). Add a network security config: no cleartext, system CAs only. This matters because minSdk 24 allows cleartext by default on API 24–27. Set `allowBackup="false"`. |
| 1.6 | **Only allow the software fallback key in tests** | `data/local/EncryptedTokenStore.kt:171-181, 212-226` | Add a constructor flag `allowSoftwareFallback = false` that only tests set to true. On a real device, a missing Keystore must fail closed, never store a raw key in prefs. |
| 1.7 | **Low-severity clean-ups** | various | Hide the PAT in `OrganizationAccount.toString()` (`data/model/WorkItemModels.kt:143`). Check `datePrefix` against `^\d{4}-\d{2}-\d{2}$` (`AzureWorkItemRepository.kt:156`). On disconnect, also delete the Keystore entry and cancel one-time work. HTML-escape plain-text descriptions before PATCHing them back (`AzureWorkItemParser.kt:19` → `AzureWorkItemCrudRepository.kt:80-86`). Accept a `MainActivity` deep-link extra only for a saved org with an id greater than 0. |
| 1.8 | **Update outdated libraries** | `gradle/libs.versions.toml` | Move OkHttp 4.10 to 4.12 or later, and also update the Compose BOM (2024.09) and lifecycle (2.8.7) to current stable versions. Add Dependabot so updates keep coming. |

## Phase 2: Performance (size M–L, biggest user-visible gains): Part A done

Notes on what was implemented in Part A (commits A1–A5):

- **A1: background work**
  - Notifications are polled only while the app is visible.
  - The background job also waits until the battery isn't low.
  - Comments are downloaded only for items the server reports as changed since the last check.
  - The comments request remembers which API variant works.
- **A2: metadata**
  - A project's settings are fetched once, even when several screens ask at the same time.
  - They're reused for 24 hours and remembered across restarts; pull-to-refresh forces a new fetch.
  - Other projects load two at a time.
  - The org's process list is reused for 10 minutes.
  - Per-type requests are skipped when the type list already includes the states.
  - Kept on purpose: per-type *process* states. Only that request reports states hidden by an inherited process (see `AzureDevOpsRepositoryPerformanceTest`).
- **A3: startup**
  - Saved tokens are decrypted once.
  - The offline cache is read in the background.
  - While a saved session loads, a blank screen shows instead of a flash of the sign-in screen.
- **A4: redraws**
  - Sync-progress values that nothing displays are no longer part of the board state.
  - Search no longer lower-cases every description on each keystroke.
  - Notification cards cache their work.
  - Long picker lists are lazy.
- **A5: network and cache**
  - Throttled reads (429/503) are retried, respecting Retry-After.
  - Up to 8 parallel requests are allowed.
  - Only the active org's snapshot stays in memory.
  - Saves are debounced.
  - Background loading uses 200 items per request.

Changed from the table below:
- **2.3:** `$expand=relations` stays. The parser's custom StackRank fallback and finding children that aren't loaded both need it.
- **2.8:** streaming decryption was dropped. AES-GCM checks the whole file before releasing data, so it saves little memory on Android.
- **2.9:** the Baseline Profile needs a device and is a separate follow-up.

Part B (load only the selected project, plus a real delta sync with full-timestamp watermarks) is next, after Part A has been checked on a phone.


| # | Fix | Where | How |
|---|---|---|---|
| 2.1 | **No disk or encryption work before the first frame** | `ui/viewmodel/WorkItemsViewModel.kt:220-222`, `WorkItemStreamDelegate.initializeSavedAccountsAndAutoConnect` (line 120), `AzureAccountRepository.getSavedOrganizations` (line 101) | Run the startup restore in `viewModelScope.launch(Dispatchers.IO)`. Keep decrypted accounts in memory inside `AzureAccountRepository` and refresh them on save; it's called from 54 places, and each call currently goes through the Keystore. Use `apply()` instead of `commit()` on the main thread. |
| 2.2 | **Stop the metadata request burst** (about 30 requests per project, repeated 2–3 times) | `data/repository/AzureProcessMetadataRepository.kt:962-1134`, `ui/viewmodel/delegates/WorkItemProcessDelegate.kt:69,109-117`, `WorkItemStreamDelegate.kt:412-416,466-480`, `WorkItemFilterDelegate.kt:656` | In `ensureProjectProcessLoaded`, merge duplicate in-flight requests (a `Map<key, Deferred>`) and skip anything restored from cache less than 24h old. Drop the per-type state calls, because states already come back inline with `$expand=states`. Reuse those results in `fetchProjectBacklogMetadata`. Fetch other projects' metadata only when they're selected, or with `Semaphore(2)`. |
| 2.3 | **Load only what the user needs** | `data/repository/AzureWorkItemRepository.kt:37,138,255`, `AzureWorkItemParser.kt:33-80`, `WorkItemStreamDelegate.kt:289-325` | Filter WIQL by the selected project. Request about 20 named fields (the ones the parser reads) instead of `$expand=relations`, and build the child list from `System.Parent`. Use batches of 200, run 2–3 in parallel, and remove the `delay(45)`. This also avoids WIQL's 20k-result cap on large orgs. |
| 2.4 | **Real delta sync** | `AzureWorkItemRepository.kt:135-193`, `WorkItemStreamDelegate.kt:405-409` | Use the full timestamp watermark (WIQL `timePrecision=true`) instead of `take(10)`, which drops the time of day. Pass in `lastSyncedChangedDate`, remove the 60-item cap, and stop re-listing every ID in the org. |
| 2.5 | **Make notification sync cheaper** | `ui/viewmodel/NotificationsViewModel.kt:87-95`, `AzureNotificationsRepository.kt:428-703`, `AzureCommentsRepository.kt:93-129`, `notifications/AzureSyncWorker.kt:100,121-123` | Replace the `while(true)` 3-minute loop with polling only while the app is in the foreground (`ProcessLifecycleOwner` + `repeatOnLifecycle(STARTED)`). Fetch comments only for items changed since `lastCheckedChangedDateIso`. Make one comments attempt, with a fallback only on 400. For the worker, add `setRequiresBatteryNotLow(true)` and compare against a small seen-IDs index instead of decrypting full snapshots. |
| 2.6 | **Network client policy** | `data/api/AzureDevOpsApi.kt:202-214` | Add a retry interceptor that honours `429 Retry-After` with exponential backoff (max 3 tries), and raise `Dispatcher.maxRequestsPerHost` to about 8. |
| 2.7 | **Fewer recompositions** | `WorkItemsViewModel.kt:140-218`, `NotificationsScreen.kt:856,990,1040`, `WorkItemPickerDialogs.kt:955`, `WorkItemsBoardHeader.kt:1039`, `WorkItemModels.kt:68`, `WorkItemCreateSheet.kt:254-263` | Split the single 33-flow `combine` into 3–4 smaller states (list, filters, sync progress, detail) so a progress tick doesn't redraw the board. `remember` the mention-highlight Regex and the RTL check. Switch long `forEach` lists to `LazyColumn`. Make `descriptionLower` a lazy property like the other lowercase fields. |
| 2.8 | **Cheaper local cache** | `data/local/LocalCacheCipher.kt:63-78`, `data/local/WorkItemLocalStore.kt:47-49`, `WorkItemCrudDelegate.kt:163-171` | Decrypt through a stream (`CipherInputStream` → Okio) instead of `readBytes()`. Merge per-edit snapshot rewrites into one write, on a 2s idle or when the app goes to the background. Keep only the active org in `inMemorySnapshots`. |
| 2.9 | **Startup profile** | new `baselineprofile` module | Add a Baseline Profile and Macrobenchmark for cold start and board scrolling. |

## Phase 3: Effectiveness and architecture (size L, do step by step)

| # | Fix | Where | How |
|---|---|---|---|
| 3.1 | **Replace the global callback bus** | `di/SessionManager.kt:48-60`, `ConnectionViewModel`, `NotificationsViewModel`, `WorkItemsViewModel.kt:228-262`, `MainActivity.kt:39-42` | Move connect, switch and disconnect into a `SessionCoordinator`, a plain class in `AppContainer` with its own app-wide scope that exposes StateFlows. All ViewModels call it directly. Delete the 9 nullable lambdas, and make the active org a single source of truth. |
| 3.2 | **Safe shared list writes** | `ui/viewmodel/delegates/*` (23 writes, e.g. `WorkItemHierarchyDelegate.kt:614,687`, `WorkItemCrudDelegate.kt:252`) | Wrap `allWorkItems` in a small `WorkItemStore` with atomic `update {}` helpers (`upsert`, `remove`, `replaceAll`). This fixes updates that can be lost when two writes overlap. |
| 3.3 | **Typed errors** | new `data/api/AzureError.kt`; existing mapping at `AzureWorkItemRepository.kt:263-268` | Add `sealed interface AzureError` (Unauthorized, Forbidden, NotFound, RateLimited, Network, Server, Unknown) with a single mapper. Replace `runCatching` in suspend code with a helper that rethrows `CancellationException`. |
| 3.4 | **Slim the god facade and inject globals** | `data/repository/AzureDevOpsRepository.kt` (about 65 public fns), `AzureDevOpsClient` object, `AzureIdentityRegistry` object | Delete the 19 unused methods and make the sub-repos private. Remove the default-argument constructor graph so the worker uses `AppContainer`. Turn the client and the registry into instances built in `AppContainer`, so tests no longer need reflection. |
| 3.5 | **WIQL builder** | 17 sites across `AzureWorkItemRepository` and `AzureNotificationsRepository` | Add a small `Wiql` helper with built-in escaping and date checking. |
| 3.6 | **Split oversized files** | 9 files over 1000 lines (e.g. `WorkItemHierarchySection.kt`, `WorkItemCreateSheet.kt`, `AzureProcessMetadataRepository.kt`) | Split them along the existing responsibility lines when they are next changed, e.g. HierarchySection → parent, children and dialogs files. Don't rewrite everything at once. |
| 3.7 | **Remove dead code and clutter** | `sync/AzureSyncWorker.kt`, the JobScheduler code in `NotificationHelper.kt`, `ui/components/WorkItemComponents.kt`, the `AzureDestination` typealias, unused colours, `app/.attach_pid1213`, the hard-coded Robolectric path | Delete them. Leave `applicationId` alone, because changing it breaks updates for installed users. Leave the signing configs alone too (AI Studio requires them). Remove the google-services and secrets plugins and `metadata.json` only if the app no longer builds through AI Studio. |
| 3.8 | **Localisation and accessibility** | about 150 hard-coded UI strings; 33 `.clickable` rows | Move the strings to `strings.xml`. Add `Role` and `onClickLabel` to the clickable rows. |

## Phase 4: Quality gates (size S–M, start alongside Phase 1): partly done
- ✅ `.github/workflows/android.yml` runs unit tests, the debug build and the R8 check on every PR. Lint is reported but doesn't block yet.
- ✅ `.github/dependabot.yml`.
- ✅ Regression tests for Phase 1: `app/src/test/java/com/example/security/SecurityHardeningTest.kt`.
- Still to do: a lint baseline, detekt/ktlint, and tests for the other areas listed below.
- **CI:** add `.github/workflows/android.yml` to run `lintDebug`, `testDebugUnitTest` and `assembleRelease` on every PR. Also add detekt/ktlint and Dependabot.
- **Tests for the riskiest areas:**
  - A connect-flow test proving a wrong PAT doesn't show the cache (regression test for 1.1).
  - Error mapping.
  - The WIQL builder.
  - `AzureWorkItemParser`.
  - The CRUD repository, against OkHttp `MockWebServer`.
  - The sync worker, using `work-testing`.
  - The atomic `WorkItemStore`.

## Default decisions (say if you want otherwise)
- **Offline scope (2.3):** offline mode will hold the *selected project*, not the whole organisation. Other projects load when you open them.
- **Token field (1.2):** a saved token can't be viewed or copied any more, only replaced.

## Verification
1. `./gradlew testDebugUnitTest`: existing and new tests pass.
2. `./gradlew assembleRelease` with R8 on, then install it and smoke-test connect, board, detail sheet, create, comments and notifications. R8 problems with Moshi and Retrofit would show up here.
3. **Security checks:**
   - A wrong PAT for a cached org shows no items and an error.
   - Notifications on the lock screen show generic text.
   - Screenshots of the Connection screen come out blank.
   - After disconnect, the cache files, Keystore alias and notifications are gone.
4. **Performance checks** (debug build with OkHttp logging and StrictMode):
   - Count startup requests: the target is under 10 per project, down from about 30 repeated.
   - No StrictMode disk-on-main-thread violations.
   - Compare cold start (`adb shell am start -W`) and Macrobenchmark scroll results before and after.
   - Background traffic stops once the app is in the background, apart from the 15-minute worker.
