# External review brief — ARK calls go public, nine new languages (2026-10-07)

You are reviewing **six commits** in the ARK-phone repository (branch
`feature/voip-spike`, commits `ffbf7a1`, `790aea8`, `ec03f85`, `e65a3c1` the DND fix
`ecf2a08` and the backup feature `8e895ca` (brief commits in between), all on top of `fe55679`, which is the 1.27 line plus two field fixes). Together they
become release **1.28**, the first public build that carries ARK internet
calls, and the first with more than two languages. Nothing here is a bug fix;
it is a scope change of the public release, and the maintainer wants
everything it touches checked before it ships.

```
git diff --stat fe55679..HEAD      # the five commits plus this brief
```

## Ground rules

- **Report only. No patches.** Every finding is verified against the code
  and fixed by the maintainer separately.
- Finding format: severity P1/P2/P3 · `file:line` · one-sentence claim ·
  concrete failure scenario · confidence. Sorted by severity. No style notes
  on code. **Translation findings are wanted at any severity** — see part D.
- Be exhaustive. This brief lists what the maintainer believes was covered;
  your value is in what it missed. Read every changed file, not only the ones
  named here.

## What changed, by commit

### 1. `ffbf7a1` — Ship ARK internet calls in every build variant

Until now the VoIP engine lived in `app/src/debug` and the `beta` build type
borrowed that source set; the GitHub release had no VoIP code, no `INTERNET`
permission and no worker address. Now:

- `app/src/debug/java/**` (29 Kotlin files, package `org.jarsi.arkphone.voip`
  and sub-packages `di`, `fcm`, `telecom`) moved verbatim to
  `app/src/main/java/**`. Git shows them as renames with no content change
  except `voip/di/VoipModule.kt` (KDoc only).
- `app/src/testDebug/java/**` (16 test files) moved to `app/src/test/java/**`.
- `app/src/debug/AndroidManifest.xml` is deleted; its 8 permissions, the
  `ArkPackageEventReceiver` (MY_PACKAGE_REPLACED + BOOT_COMPLETED), the
  `VoipForegroundService` (`phoneCall|microphone`) and the
  `ArkMessagingService` (FCM) are merged into `app/src/main/AndroidManifest.xml`.
- **New** in the main manifest: `<meta-data
  android:name="firebase_messaging_auto_init_enabled" android:value="false"/>`.
  Intent: the app must make **no network connection at all** until the user
  creates an ARK code (the README promises this). Firebase would otherwise
  mint an installation id + FCM token at process start. The token is fetched
  on demand in `voip/fcm/ArkFcmRegistration.refresh()` via
  `FirebaseMessaging.getInstance().token`, which the maintainer believes still
  works with auto-init off. **Verify this against the Firebase Messaging
  25.1.1 behaviour**, and verify that nothing else in the app opens a socket
  before registration (OkHttp client construction, Hilt singleton creation at
  startup, `ArkVoipStartup` gating on an existing identity, Firebase
  Installations, WebRTC `PeerConnectionFactory` initialisation, Coil).
- `app/build.gradle.kts`: `VOIP_WORKER_URL` is now a `defaultConfig`
  BuildConfig field, default `https://arkphone-voip.jarsi.workers.dev`,
  overridable through `arkphone.voip.workerUrl` in `local.properties`. The
  five VoIP dependencies (`androidx.core:core-telecom`, `firebase-messaging`,
  `okhttp` 5.4.0, `kotlinx-serialization-json`, `io.getstream:stream-webrtc-android`
  1.3.10) are `implementation` for every variant. The `beta` source-set block
  is gone (beta is plain release-signed + unminified now).
- `di/AppModule.kt` keeps its three `@BindsOptionalOf` VoIP bindings
  (comment changed only). `VoipModule` fills them in every variant now.
- New test `voip/fcm/FcmManifestTest.kt` reads the merged manifest meta-data
  under Robolectric and asserts auto-init is off.

**The release build is minified (R8, `proguard-android-optimize.txt`,
`isShrinkResources = true`) and this is the first time the VoIP stack goes
through it.** The maintainer relies on the bundled consumer rules of the
libraries (stream-webrtc ships `-keep class org.webrtc.** { *; }`;
kotlinx-serialization and OkHttp ship their own) and has **no** VoIP rules in
`app/proguard-rules.pro`. A minified 1.28 build installed and launched on a
Pixel 8a without crashing; a live ARK call on the minified build is still
pending. Specifically review for R8 breakage:

- `voip/SignalingMessage.kt` — `@Serializable` sealed/data classes decoded
  with `SignalingMessage.serializer()` and a configured `Json`; polymorphic
  `type` discriminator handling; any `@SerialName` or default values that
  R8 full mode could drop.
- `voip/TurnCredentials.kt`, `voip/ArkAccountClient.kt`,
  `voip/FlushReconciler.kt` — JSON parsing of worker responses.
- Reflection or JNI surfaces: `PeerConnectionFactoryProvider`,
  `StreamPeerConnectionAdapter` (callbacks implemented as Kotlin objects —
  are the interface methods kept?), `WebRtcCallSession`.
- `androidx.core.telecom` (`CoreTelecomRegistrar`, `VoipCallHandle`) and
  `FirebaseMessagingService` subclass (`ArkMessagingService`) — manifest
  references survive, but check callbacks and `Bundle` keys.
- Anything keyed by enum or class *name* at runtime (`valueOf`, Room
  entities — `ArkCallLogWriter`, `CallLogEntry` ARK feature flag).
- `isShrinkResources` removing a resource only referenced from VoIP code.

Also review, now that this code runs on **every** phone and not just the
maintainer's family:

- `ArkPackageEventReceiver` on BOOT_COMPLETED / MY_PACKAGE_REPLACED: what it
  does for a phone that never created an ARK code (it must do nothing and
  touch no network), and what it costs on a phone that did.
- `VoipForegroundService` start paths on Android 14/15 (foreground-service
  type `phoneCall|microphone`, `FOREGROUND_SERVICE_MICROPHONE` restrictions
  when started from the background / from an FCM wake).
- `ArkVoipStartup` keeps a websocket to the worker whenever an identity
  exists: reconnect/backoff behaviour on flaky networks, battery, and what
  happens when the worker rate-limits or is down.
- Permission UX on first use: `RECORD_AUDIO`, `POST_NOTIFICATIONS`,
  full-screen-intent — `ui/settings/ArkCallsScreen.kt` /
  `ArkCallsViewModel.kt`; the user must never end up with a silent incoming
  call they cannot see.
- The default URL in a public repo: anyone can register (the worker
  rate-limits at 20 registrations/hour globally and 5 per IP per hour; it
  stores nickname, code, public key, a bearer-token hash and the FCM token).
  Say if you see an abuse path in the **app** (the worker is a separate
  private repo — reason about it from `voip/ArkAccountClient.kt`,
  `voip/SignalingClient.kt` and `voip/WorkerVoipAccountGateway.kt`).

### 2. `790aea8` — Nine new languages

`app/src/main/res/values-{sv,de,fr,es,et,ru,pt,it,pl}/strings.xml`, each a
full translation of the 260 translatable strings and 8 plurals in
`values/strings.xml` (the 5 `translatable="false"` strings are omitted, as in
`values-fi`). `res/xml/locales_config.xml` and the Gradle `localeFilters`
list name all eleven locales; `LocalesTest.kt` asserts the two lists match,
that every listed language translates `recents_empty`, and that an unlisted
language (`ja`) falls back to English. Lint (`warningsAsErrors`) passes,
including `MissingTranslation`, `MissingQuantity`, `ImpliedQuantity`,
`TypographyDashes`.

Plural quantity sets used: sv/de/et `one,other`; fr/es/pt/it
`one,many,other`; ru/pl `one,few,many,other`. Because lint's `ImpliedQuantity`
flags a `one` item without a number for locales where `one` also covers 0
(fr, pt) or 21/31/… (ru), those `one` items carry the number
(`Supprimer %1$d conversation ?`, `%d appel manqué`, `Удалить %1$d беседу?`),
and the two text-only plurals (`conversations_delete_confirm_text`,
`messages_delete_confirm_text`) carry `tools:ignore="ImpliedQuantity"` in
fr, pt and ru.

Conventions chosen (challenge them if wrong for a language):

- The app name is localised the way Finnish already does it
  (`ARK-puhelin`): `ARK-telefon` (sv, et, pl), `ARK-Telefon` (de),
  `ARK-téléphone` (fr), `ARK-teléfono` (es), `ARK-телефон` (ru),
  `ARK-telefone` (pt), `ARK-telefono` (it). The same word is used inside
  every sentence that names the app.
- Address: informal "du/tu/ty" where Android itself is informal (sv, de, es,
  it, et, pl, pt), formal "vous"/"вы" for fr and ru. The three canned
  reject-SMS replies are informal everywhere (they go to a friend).
- `values-pt` is Brazilian Portuguese (Android's bare `pt`), not pt-PT.
- Blocked-prefix / allowed-number examples use a local premium prefix and a
  plausible local mobile number per country (`+46900 eller 0900`,
  `+49900 oder 0900`, `+33899 ou 0899`, `+34803 o 803`, `+372900 või 900`,
  `+7809 или 8809`, `+55900 ou 0900`, `+39899 o 899`, `+48700 lub 700`).
  Say if any is not a real premium-rate prefix in that country.
- Durations: `%1$d h %2$d min` kept except de (`Std.`/`Min.`), ru (`ч`/`мин`/`с`),
  pl (`godz.`).
- `incall_mute` in ru is `Микрофон` (the toggle label); flag it if Android's
  own dialer wording is better.
- Swedish repeat interval uses the ordinal form `var %1$d:e sekund` (the
  value is clamped to 4–10, so `:e` is always right).

### 3. `ec03f85` — README rewrite

`README.md` now claims, in the Privacy section, that:

1. there is no analytics / crash reporting / ads / third-party SDK phoning home;
2. calls, messages and contacts stay in Android's system stores;
3. **until the user creates an ARK code the app makes no network connection
   at all**;
4. with ARK calls on, the signaling server stores nickname, ARK code, public
   key and push token, sees the IP while connected, forwards call-setup
   messages and never carries audio;
5. the FCM push carries only the caller's ARK code (worker sends
   `data: { type: "incoming-call", caller: <code> }`);
6. audio is end-to-end encrypted (WebRTC DTLS-SRTP), phone to phone, with a
   TURN relay only when no direct path exists, and the relay cannot decrypt.

**Check each claim against the app code** and report any that is not
literally true (for example a Firebase or Play-services component that
connects regardless of claim 3, or any VoIP data the app sends that claim 4
does not list, such as the linked contact's phone number, nicknames of
contacts, or call metadata).

### 4. `e65a3c1` — version 1.28, versionCode 16

Nothing else.

### 5. `ecf2a08` — Do Not Disturb fix: the incoming-call notification names the caller

Field observation (Pixel 8a, 2026-10-07 19:5x, minified 1.28 release): with
Do Not Disturb on and its policy set to "calls from starred contacts only"
(`dumpsys notification`: `priorityCallSenders=PRIORITY_SENDERS_STARRED`,
`suppressedVisualEffects=SCREEN_OFF,SCREEN_ON,FULL_SCREEN_INTENT,LIGHTS,PEEK,AMBIENT`),
an incoming ARK call from the 10 Pro showed only as a silent row in the
notification shade: no lock-screen ring, no heads-up. A carrier call from the
same (unstarred) contact would have been silenced too, but its call screen
still opens through Telecom. The structural gap: `CallNotifications.
buildIncomingCall()` built a `Person` for the title only and never attached
it to the notification, so DND's people matching (`EXTRA_PEOPLE_LIST`) had
nothing to match — even a **starred** contact's ARK ring would stay silent
while that person's carrier call rang.

Fix (TDD, two tests in `telecom/CallNotificationsTest.kt`):

- the `Person` gets `setUri("tel:" + info.number)` when the call carries a
  number (ARK calls do: the linked contact's number via `numberForCode`);
- the builder calls `.addPerson(caller)` for every incoming-call
  notification, carrier and ARK alike.

Review: (a) is `EXTRA_PEOPLE_LIST` with a raw, unnormalised `tel:` URI what
`ZenModeFiltering` / `ValidateNotificationPeople` match against the contacts
provider on Android 12–17, or does it need E.164 / `PhoneNumberUtils`
normalisation (the numbers come straight from the call log / contact card,
e.g. `+358 44 5552841` with spaces)? (b) any side effect of `addPerson` on the
carrier-call notification (ranking, conversation treatment, the "quiet"
variant that must stay silent)? (c) is there a better platform path for a
self-managed call to ring under DND than the people list?

### 6. `8e895ca` — Backup to a file (new feature)

Design: `docs/specs/2026-10-07-backup-file-design.md` (read it first; it is
short). Settings → Backup → "Save backup to file" / "Restore from file".

- `backup/BackupSnapshot.kt` — payload model + hand-built JSON (typed
  preference entries, ARK links, WhatsApp calls).
- `backup/BackupCodec.kt` — envelope, PBKDF2WithHmacSHA256 (600 000
  iterations, 16-byte salt, 256-bit key) + AES/GCM/NoPadding (12-byte IV,
  128-bit tag, AAD `arkphone-backup:1`), `BackupError` sealed type, caps:
  16 MB file, 5 000 000 iterations.
- `backup/BackupStore.kt` — snapshot from the `settings` DataStore (generic:
  every key/type/value except `ark_synced_fcm_token`) + Room; restore =
  one `dataStore.edit { clear(); put… }` + one `withTransaction` replacing
  `ark_links` and `whatsapp_calls`.
- `voip/VoipEngine.dropClient()` + `voip/ArkVoipStartup` now keys on the
  identity **code** (`map { it?.code }.distinctUntilChanged()`) and drops
  the client before every (re)connect, so a restore that changes the code
  reconnects under it. Previously it keyed on presence only.
- `ui/settings/BackupViewModel.kt` (ContentResolver + SAF URIs, IO
  dispatcher, `BackupUiState` busy/message/pendingRestore) and
  `BackupScreen.kt` (password switch on by default, repeat field, red
  warning when off, `CreateDocument`/`OpenDocument`, confirmation dialog).
- DAO additions `ArkLinkDao.all()/clear()`, `WhatsAppCallDao.insertAll()/clear()`.
- 27 new strings in all eleven languages (`settings_backup_*`, `backup_*`).
- Tests: `BackupCodecTest` (7), `BackupStoreTest` (2), `BackupViewModelTest`
  (4), `ArkVoipStartupTest` (+1).

Review especially:

1. Crypto: parameter choices, AAD use, anything that lets a crafted file
   do harm (iteration cap, size cap, JSON parsing of attacker content), and
   whether a wrong password is distinguishable from damage (it must not be).
2. The unencrypted option: the file then holds the ARK **device token** in
   the clear; the screen warns in red. Is the warning enough, and is there
   any way the token leaks elsewhere (logs, crash text, share sheet)?
3. SAF handling: `openOutputStream(uri, "wt")` on a `CreateDocument` URI;
   a failed write leaves an empty file at the chosen location; no persisted
   URI permission is taken (none needed?). Reading a `*/*` pick from any
   provider.
4. Restore atomicity across the two stores (one DataStore edit, one Room
   transaction — not a single unit); restore while an ARK call is active
   (`dropClient()` tears the signaling client down under a live call —
   should the screen refuse while a call is in progress?); the generic
   preference round trip (Int vs Long, Float vs Double, Set<String>) and
   the `DEVICE_ONLY_KEYS` exclusion on both export and import.
5. `dropClient()` ordering inside `connectMutex` versus `startCollecting`
   and the drain state; any leak of the old `SignalingClient` jobs.
6. Compose screen state (`rememberSaveable` passwords survive rotation — is
   keeping a typed password in saved instance state acceptable?), and the
   snackbar/`LaunchedEffect(message)` pattern.
7. Part D applies: the 27 new strings in all nine new languages plus
   Finnish.

## Not changed, for orientation

- The worker (Cloudflare Worker + two Durable Objects, TURN credentials from
  Cloudflare Realtime) is unchanged and already live; the app's protocol with
  it is unchanged. Registration is open.
- `app/proguard-rules.pro` is unchanged (no VoIP rules).
- The in-app ARK settings screen, contact-card linking and the call UI are
  unchanged; they were already in `main` behind the optional bindings.
- `ark_calls_unavailable` ("not available in this build") is still shown
  when the optional binding is empty, which can no longer happen in any
  shipped variant — harmless, flag it only if you see it reachable.

## Verification done by the maintainer

- `./gradlew :app:testDebugUnitTest :app:lintDebug` — 930 tests, 0 failures,
  lint clean.
- `./gradlew :app:assembleRelease` — minified, signed, 46 MB (libwebrtc for
  four ABIs; the 1.27 release was 1.9 MB). Installed on a Pixel 8a (Android
  17) and opened; no crash in logcat.
- A script compared every translation file with `values/strings.xml`: same
  string and plural names, identical format placeholders, no unescaped
  apostrophes.
- **Pending:** a live ARK call on the minified build; this is the one thing
  the maintainer cannot assert yet.

## What a good report looks like

Part A — code findings on commits 1, 5 and 6 (R8, startup network use,
permissions, background execution, abuse, the DND people matching, the
backup crypto/file/restore paths), in the finding format above.

Part B — README claims that are false or incomplete, each with the code that
contradicts it.

Part C — anything in the Gradle/manifest/test wiring that would break a
clean checkout (`google-services.json` absent, `local.properties` absent) or
the beta variant.

Part D — translations: for **each** of the nine languages, list every string
that is wrong, unnatural, inconsistent with Android's own wording for the
same concept, uses the wrong register, has a plural form that is wrong for
the CLDR category, or whose example number/prefix is wrong for the country.
Quote the current text and give the corrected text. A language with nothing
to fix should say so explicitly so the maintainer knows it was read.

## Round 1 (2026-10-07 21:09) — findings and what was done

Codex reviewed `fe55679..325232f` (the committed state; the then-uncommitted
`BackupSanitizer` was not part of it) and reported 1 P1, 10 P2, 7 P3. Every
finding was verified against the code and found real. Fixes, all TDD
(967 tests, lint clean):

| # | Finding | Fix | Commit |
|---|---|---|---|
| P1 | `onAppStart()` called `fcmRefresh()` before any identity → Firebase registration pre-registration | the refresh moved inside the identity branch; test `startupWithoutAnIdentityNeverAsksFirebaseForAToken` | `896f039` |
| P2 | restore wrote a known key with the wrong type → `ClassCastException` on every launch | `BackupSanitizer` registry of known keys **with types**; unknown keys and wrong types dropped; reflection test guards the registry against the repositories' key objects | `9783317` |
| P2 | malformed JSON shapes escaped as `IllegalArgumentException` / `IllegalStateException` | codec wraps every parse path (`asBackupError`), type-checks envelope fields; 12-case test | `9783317` |
| P2 | PBKDF2 / parsing on Main | the whole export/restore block runs under `withContext(io)`; test asserts the snapshot runs on the IO thread | `9783317` |
| P2 | identity replaced under a live ARK call | `BackupViewModel.restore()` refuses with `BackupError.CallInProgress` while `CallController.calls` is non-empty; new string in 11 languages | `9783317` |
| P2 | prefs committed, Room rolled back; `viewModelScope` cancellation | tables first (transaction), prefs second (one edit); prefs failure puts the tables back from captured rows; whole apply under `NonCancellable`; tests for closed DB, failing DataStore and cancelled caller | `9783317` |
| P2 | null identity skipped `dropClient()` | drop on every identity change, connect only when non-null; test `removingTheIdentityClosesTheInboxSocket` | `896f039` |
| P2 | flush drain not cancelled with its client | `drainJob` tracked and cancelled in `dropClient()`; test `aDroppedClientsDrainCannotRingTheReplacementEarly` | `896f039` |
| P2 | FCM sync marker written for a replaced identity | `FcmTokenSync.sync()` re-reads the identity after the POST and records the marker only for the one it posted for; test with a stalled POST | `896f039` |
| P2 | stale `blocking_sim_account_id` disables rules | `BackupStore` drops `call_sim_account_id` / `blocking_sim_account_id` not present in `SimAccountRepository.accounts()` | `9783317` |
| P2 | export unbounded while import caps at 16 MB | snapshot takes the newest 10 000 WhatsApp calls (`WhatsAppCallDao.newest`); export refuses to write a file over the import cap | `9783317` |
| P3 ×7 | fr / et / ru wording | applied as suggested | `bcdaae4` |

Decisions worth a second look: unknown preference keys are now **dropped**
on restore (a backup from a newer app loses keys the older app does not
know — the older app could not read them anyway); a restore with no SIM
inserted drops both SIM ids; an oversized export reports the generic
"could not be read or written" message rather than a dedicated one.

## Round 2 request

Review the three fix commits `896f039`, `bcdaae4`, `9783317` on top of
`325232f`, with the same ground rules. Concentrate on:

1. Whether each round-1 fix is complete and introduces nothing new —
   especially the restore ordering/compensation in `BackupStore.restore()`
   (is the captured-rows rollback itself safe if the second
   `replaceTables` fails?), the `NonCancellable` scope, and the
   `FcmTokenSync` identity re-check (does it leave a legitimate token
   unposted in any ordering?).
2. `BackupSanitizer`'s registry versus every reader: is any accepted value
   still able to misbehave downstream (`speed_dial_N` numbers in dial
   intents, SIM ids fed to Telecom, long nicknames in notifications)?
3. `VoipEngine.dropClient()` with a drain cancelled mid-flight: can a
   message buffered by the old client be lost rather than merely not rung?
4. The `CallInProgress` guard: `CallController.calls` covers carrier and
   ARK calls alike — is a carrier call a reason to refuse, and is there a
   ringing-but-not-yet-added window it misses?
5. Part D for the new string `backup_error_call_in_progress` in all nine
   languages plus Finnish, and for the seven corrected strings.

## Round 2 (2026-10-07 21:46) — findings and what was done

Codex reviewed `896f039`, `bcdaae4`, `9783317` and reported 5 P2, no
translation corrections. Every finding was verified against the code and
found real. Fixes, all TDD (983 tests, lint clean), on 2026-10-08:

| # | Finding | Fix | Commit |
|---|---|---|---|
| P2 | `ark_device_token` only length-checked; `token\ncontrol` made OkHttp throw on the Authorization header at every connect, taking the startup coroutine with it | the sanitizer accepts only the base64url shape the worker issues (`^[A-Za-z0-9._~-]{1,256}$`) and drops the whole identity otherwise; `OkHttpWebSocketConnector` reports a bearer it cannot send as a failed connect (1006, on a dispatcher thread) instead of throwing | `43225d3` |
| P2 | `runCatching { replaceTables(previous) }` hid a failed rollback: imported rows next to the old preferences, original rows gone | the preferences edit now runs **inside** the Room transaction that replaces the tables: a failed preferences write is rolled back by SQLite itself, there is no compensating write left to fail. Test fault-injects framework SQLite (writes refused on demand from inside the failing DataStore) | `93c8b3b` |
| P2 | `WhatsAppCallMonitor` could insert between the table replacement and the preferences write; the rollback deleted the real call | same transaction: the SQLite write lock keeps every other table writer out until the outcome is known, so the call lands on whichever tables survive. Test races a real insert on `Dispatchers.IO` against the restore | `93c8b3b` |
| P2 | the call check preceded file read / PBKDF2 / SIM query; an ARK call arriving meanwhile was dropped by the identity swap | two-step restore: "Restore" decodes + sanitises off Main and the confirmation shows what the file brings; confirming applies it under a hold on ARK call admission (`ArkCallAdmission`, implemented by `VoipCallCoordinator`, taken on Main before the carrier-call check): a live or ringing ARK call refuses the hold, an incoming ARK call under the hold is dropped (caller's connect timeout → carrier), an outgoing one goes to the carrier, released however the apply ends | `fa64256` |
| P2 | identity re-read and `setSyncedFcmToken` were two DataStore operations; a restore between them marked B synced for A's POST | `markFcmTokenSynced(identity, token)` checks the stored identity and writes the marker in ONE `dataStore.edit`; the marker is bound to its account (`ark_synced_fcm_account`, device-only) so one left by another identity never satisfies the unchanged-token shortcut. Tests use the real repository over an in-memory `DataStore` with a hook that lands a restore right before the marker's edit | `918eec3` |
| own | speed dials, link numbers and WhatsApp numbers are dialled; an MMI sequence could ride in on a file | they must match `^\+?[0-9][0-9 ()-]{0,63}$`; a link's `numberKey` must equal `arkLinkKey(number)` | `43225d3` |
| own | a foreign backup was indistinguishable until applied | the confirmation lists the ARK code (with nickname) and the number of linked contacts the sanitizer will keep, or "no ARK code"; 2 new strings × 11 languages | `fa64256` |

Decisions worth a second look:

- The restore-journal alternative (durable undo/redo log in
  `filesDir/no_backup`, replayed at the next start) was rejected in favour
  of the single transaction: it needed a startup hook that itself races
  the table writers, and its only extra coverage is a process death
  between the preferences file's rename and the SQLite commit (new
  preferences next to old tables). That window is documented in
  `BackupStore.restore()` and the spec, not closed.
- The DataStore edit runs on the Room transaction thread (`withTransaction`
  → `runBlocking` on the transaction executor; the DataStore's actor runs
  on `Dispatchers.IO` in production). The view-model test therefore uses an
  in-memory `DataStore` (`InMemoryPreferencesDataStore`), since there Room's
  executors are the test dispatcher and a file-backed store would need the
  same thread.
- `holdForRestore()` refuses on `active != null` only — a call the
  coordinator has already handed to Telecom but not yet rung counts; a
  carrier call is still checked through `CallController` under the hold.
- The FCM marker is bound by `code`, not by `code.deviceToken`: the marker
  states what the worker's row for that code holds. The write guard
  compares both code and device token.

## Round 3 request

Review the five fix commits on top of `6672da4` with the same ground
rules. Concentrate on:

1. `BackupStore.restore()`: the DataStore edit inside the Room transaction.
   Can it deadlock in production (DataStore actor on `Dispatchers.IO`,
   Room transaction executor = `ArchTaskExecutor` IO pool)? Does a
   `SQLiteConnectionPool` wait by a concurrent writer (the monitor's
   `insert`, the link screen's `upsert`) interact badly with the 30 s
   "connection pool busy" diagnostics or with Room's invalidation tracker?
   Is the residual process-death window acceptable for a P2, or does it
   deserve the journal after all?
2. `ArkCallAdmission`: every path by which an ARK call becomes `active`
   or reaches Telecom — is there one that neither `holdForRestore()` nor
   the two `heldForRestore` checks in `onIncoming()`/`startCall()` sees
   (the FCM wake path, `VoipEngine` flush reconciliation, a call answered
   from the notification)? Is the hold released on every exit of
   `applyRestore()`, including `viewModelScope` cancellation?
3. `DataStoreArkIdentityRepository`: with the marker bound by code, is
   there an ordering (registration after a restore that removed the
   identity, a re-registration under the same code, a backup from the same
   phone restored onto itself) in which a token the worker does not hold
   is reported as synced, or a held one is posted forever?
4. `BackupSanitizer`: the new patterns against every reader — can a value
   that passes `^\+?[0-9][0-9 ()-]{0,63}$` still misbehave in
   `PhoneCaller.placeCall()` / `Uri.fromParts("tel", …)`, in
   `PhoneNumberUtils.compare`, or in `arkLinkKey`? Is dropping a user's own
   speed dial that contains `#` (a saved USSD contact) acceptable?
5. The two-step restore in `BackupViewModel`/`BackupScreen`: a decoded
   snapshot kept in the view model across a configuration change, a second
   `chooseRestore()` while a preview is open, `busy` during the decode, and
   the dialog driven by `pendingRestore.preview` state.
6. Part D for `backup_restore_details_identity` and
   `backup_restore_details_no_identity` in all nine languages plus Finnish
   (the `%1$s` is "ARK-XXXX-XXXX (nickname)" composed in code).

## Round 3 (2026-10-08 08:00) — findings and what was done

Codex reviewed the five round-2 fix commits on top of `6672da4` and
reported 5 P2, all in the restore/admission work. Every finding was
verified against the code and found real. Fixes, all TDD (990 tests,
lint clean, debug APK + release/beta variants build):

| # | Finding | Fix | Commit |
|---|---|---|---|
| P2 | the number pattern required a leading digit, so "(212) 555-0123" — the spelling the contact picker and the link screen store — lost its speed dial, link and WhatsApp rows on restore | `^\+?[0-9 ()./-]{1,64}$` with at least one digit: any contact-card spelling, still no `*` `#` `,` `;` | `bb35206` |
| P2 | a failed SQLite COMMIT after the preferences rename (disk full, no process death needed) left new preferences next to old tables | `RestoreJournal` (no-backup dir): the sanitised snapshot is journalled before anything changes and the preferences carry `backup_restore_id` (device-only). A failed commit is retried at once in a new transaction; a retry that fails too leaves the journal and `recoverInterruptedRestore()` at process start (`ArkPhoneApp`) replaces the tables from it when the preferences carry its id, or discards it when they do not. A clean failure deletes the journal. Test fault-injects the outermost exclusive transaction's COMMIT (the DAOs and the invalidation tracker use non-exclusive ones — the first attempt hit the tracker's `syncTriggers` transaction instead), with and without the retry failing | `66beb24` |
| P2 | `onIncoming()` that suspended on the link cache / rules while a whole restore came and went found the hold released and rang under the new client | `restoreGeneration` counter, bumped by `holdForRestore()`, captured at admission and compared before `ring()` | `0451fbb` |
| P2 | `holdForRestore()` granted the hold to a second restore while one was held; the first's release exposed the second's swap | refused while a hold is held (one restore at a time) | `0451fbb` |
| P2 | the preview dialog stayed open during the apply with Cancel pressable (it only forgot a snapshot the apply no longer read) | `applyRestore()` clears the preview (and the decoded snapshot) synchronously with the confirmation, so the dialog closes and the screen shows the busy state; on failure the chosen file stays for another try | `8a0dc4b` |

Decisions worth a second look:

- Journal replay replaces the tables wholesale; a WhatsApp call recorded
  in the milliseconds between process start and the replay's transaction
  would be lost. Accepted for a path that only runs after a crash or a
  disk-full COMMIT.
- The journal carries the device token in the clear, like the preferences
  file beside it; it lives for the milliseconds of one apply unless that
  apply is cut short.
- A journal this version cannot parse is deleted (it is written before the
  transaction begins, so a corrupt one means nothing changed).
- The `backup_restore_id` preference stays after a successful restore; it
  is device-only (never exported, dropped from files as unknown).

## Round 4 request

Review the five round-3 fix commits with the same ground rules.
Concentrate on:

1. `BackupStore.restore()` / `recoverInterruptedRestore()` /
   `RestoreJournal`: every ordering of {journal write, table replacement,
   preferences rename, SQLite COMMIT, retry, journal delete, process death,
   next start}. Is there one that ends with the two stores disagreeing and
   no journal, or with a journal that replays the wrong direction? Is the
   `preferencesCommitted` flag set at the right point (after
   `dataStore.edit` returns)? Can the replay at start race `ArkVoipStartup`
   or the link cache in a way that matters?
2. The fault injection in `BackupStoreTest.FaultyWrites`: does it model
   a real failed COMMIT faithfully (success swallowed at depth 1, throw
   after the real `endTransaction`), and is the depth tracking right for
   Room 2.8's mix of exclusive (`withTransaction`) and non-exclusive
   (`performSuspending`, invalidation tracker) transactions?
3. `VoipCallCoordinator`: with `restoreGeneration` and the one-at-a-time
   hold, any remaining path by which a call admitted before a restore rings
   after it, or by which a hold is never released.
4. `BackupViewModel.applyRestore()` after the preview is cleared: a second
   `applyRestore()` press, `chooseRestore()` during the apply, and the
   screen's "Working…" state.
5. Whether `^\+?[0-9 ()./-]{1,64}$` still lets anything through that
   `PhoneCaller.placeCall()` or `Uri.fromParts("tel", …)` would treat as
   other than a dialable number.

## Round 4 (2026-10-08 08:46) — findings and what was done

Codex reviewed the round-3 fixes twice (two reports, overlapping) and
reported 5 distinct P2 and 1 P3, all in the restore journal and its
startup path. Every finding was verified against the code and found real.
Fixes, all TDD (1 001 tests, lint clean, debug APK + release/beta build):

| # | Finding | Fix | Commit |
|---|---|---|---|
| P2 | a new restore overwrote the journal of one that could not be finished (recovery failed at start, the app went on); the new one's clean failure then deleted it | `restore()` first finishes any pending journal under the table lock and is refused (the recovery's exception) when that is not possible, so it never takes the journal's place | `c9e1610` |
| P2 | startup recovery read journal A, suspended on the preferences, and then deleted whatever journal was there — the user's restore B had written its own meanwhile | `TableWriteLock` (one mutex, singleton): `restore()` and `recoverInterruptedRestore()` run under it, so B queues behind the recovery | `c9e1610` |
| P2 | a WhatsApp call queued behind the failed COMMIT landed between the rollback and the retry and was wiped by the retry's `clear()` | every table writer (`RoomWhatsAppCallLogRepository.record/delete*`, `RoomArkLinkRepository.link/unlink`) takes the same lock, which the restore holds across both attempts; the call lands on whichever tables the restore leaves. Test records through the repository from another thread during the first transaction | `c9e1610` |
| P2 | `voipStartup.onAppStart()` did not wait for recovery; `ArkLinkCache.await()` only guarantees a first emission, so a call could be checked against old links under the restored identity | `BackupRecoveryStartup` takes the ARK admission hold on Main before launching the recovery and releases it after (also on failure); `releaseRestoreHold()` is now suspend and refreshes the link cache (`ArkLinkCache.refresh()` re-reads the table) before opening admission — for the startup path and the user's restore alike | `c9e1610` |
| P2 | `journal.read()` (file + JSON of up to 12 000 rows) ran on Main, the application scope's dispatcher, before the first suspension | `recoverInterruptedRestore()` runs under `withContext(io)` (`@IoDispatcher`), lock and all | `c9e1610` |
| P3 | `FaultyWrites` tracked transaction depth across threads; a non-exclusive begin on another thread blocked after incrementing, so the restore saw depth 2 and committed | depth and arming are `ThreadLocal`, moved only after the real begin returned; arming consumes `failNextCommit` so one thread owns the fault | `c9e1610` |
| own | Room reports a refused write as `SQLiteException` (a RuntimeException the view model did not map) — the apply's coroutine died with it | mapped to `BackupError.Io`; test | `c9e1610` |

Decisions worth a second look:

- The table lock is a coroutine `Mutex` taken by the two repositories'
  write methods and by the restore; reads (flows, `callsOnce`) are not
  serialised. `BlockedNumbersMigration` writes the DataStore, not Room.
- A restore refused because an earlier one is still unfinished reports the
  generic "could not be read or written"; the journal is retried at every
  start and by every later restore attempt until the database writes again.
- `releaseRestoreHold()` swallows a failed cache refresh (logged) so the
  hold can never stick; the cache then catches up through its collector.
- `BackupRecoveryStartup.onAppStart()` holds admission at every start, for
  the duration of a journal stat + read on IO (no journal: sub-millisecond).

## Round 5 request

Review the round-4 fix commit with the same ground rules. Concentrate on:

1. `TableWriteLock`: every writer of `whatsapp_calls` and `ark_links`
   really goes through the two repositories; any path (a DAO injected
   elsewhere, a migration, Room's own invalidation) that writes without the
   lock; and whether holding a coroutine mutex across `withTransaction`
   (which parks a transaction thread) can deadlock against a writer that
   holds the mutex and waits for Room's transaction executor.
2. The pending-journal rule in `restore()`: the user keeps getting "could
   not be read or written" until the database writes again — is there a
   case where the journal can never be finished (its snapshot no longer
   valid for this phone, a SIM gone, a version mismatch) and the user is
   locked out of restoring for good?
3. `BackupRecoveryStartup` + `ArkVoipStartup` ordering on a cold FCM wake:
   the hold is taken in `Application.onCreate` before `onAppStart()`; the
   FCM service's `awaitWake()` and the engine's flush reconciliation —
   can a call be reconciled and rung before the hold is in place, or
   dropped (carrier fallback) at every wake for the duration of a long
   journal replay?
4. `ArkLinkCache.refresh()` versus its collector: a refresh racing the
   collector's next emission (older snapshot overwriting the newer).
5. Anything in the two repositories' `withLock` wrappers that changes
   their cancellation or exception behaviour for existing callers
   (`WhatsAppCallMonitor`, the call detail delete paths, the link screen).

## Round 5 (2026-10-08 09:41) — findings fixed by the reviewer, and what followed

Codex reviewed `c9e1610` and this time applied its fixes in the working
tree (three P2 plus the journal's error handling, sixteen regression
tests); its own report is `2026-10-08-backup-release-fixes.md`. The tree
was verified here (1 017 tests, lint clean, `git diff --check`) and
committed as `2cca4fe`:

| # | Finding | Fix | Commit |
|---|---|---|---|
| P2 | a repository write could land beside a journal still pending after a failed startup recovery | `TableWriteLock.withLock` finishes the pending restore before every write and refuses the write when it cannot; `BackupStore` is resolved lazily (`dagger.Lazy`) to break the injection cycle | `2cca4fe` |
| P2 | `snapshot()` read the two stores outside the lock, so an export could carry an unfinished restore | `snapshot()` takes the restore lock and finishes a pending journal first | `2cca4fe` |
| P2 | `ArkLinkCache.refresh()` read the table once while the old collector's late emission could overwrite it | `refresh()` replaces the subscription (cancel + join, under a mutex and `NonCancellable`) and awaits the new one's first result; a query failure clears the stale links and reaches the caller | `2cca4fe` |
| — | an unreadable journal was treated as absent; a failed delete went unnoticed | `RestoreJournal.read()` throws `IOException`, `delete()` uses `Files.deleteIfExists` | `2cca4fe` |

The maintainer's review of those fixes found one gap and fixed it (TDD,
1 020 tests):

| # | Finding | Fix | Commit |
|---|---|---|---|
| P1 | with a pending journal that cannot be finished, every table write now throws; `WhatsAppCallMonitor` (application scope) and `ContactCardViewModel` (view-model scope) did not catch it — the process would have died at the end of every WhatsApp call and on every link attempt for as long as the journal stayed pending | the monitor logs and skips the row; the contact card returns to the code entry with `ArkLinkError.STORAGE_FAILED` ("Could not save the link. Try again.", new string × 11 languages) and a failed unlink keeps the link | `18c7538` |

Decisions worth a second look:

- Refusing every table write while a journal cannot be finished is the
  reviewer's stance and is kept: it never loses data, at the price of a
  WhatsApp call log that stops growing and links that cannot be changed
  until the disk writes again. The user sees the "could not be read or
  written" message on the backup screen and the new link error.
- `BackupStore.snapshot()` now runs under the restore lock: an export
  waits for a restore in flight and vice versa.

## Round 6 request

Review `2cca4fe` and the follow-up commit with the same ground rules. This
is meant to be the go/no-go round for v1.28: besides anything new, state
explicitly whether the restore/recovery design is now sound enough to
release, or list what still blocks it. Concentrate on:

1. Every caller of `RoomWhatsAppCallLogRepository` and
   `RoomArkLinkRepository` (the monitor, the contact card, the call detail
   delete paths, the history delete paths): does each handle a refused
   write, and does any of them run on a scope where an exception would
   still crash the process?
2. `TableWriteLock(Lazy<BackupStore>)`: construction order under Hilt at
   process start (`ArkPhoneApp` injects `BackupRecoveryStartup` →
   `BackupStore` → `TableWriteLock` → `Lazy<BackupStore>`), and
   `finishPending()` running on every write — its cost on the hot path
   (`withContext(io)` + a file stat per WhatsApp call) and any reentrancy
   (a write issued from inside `finishPending`'s own transaction).
3. `ArkLinkCache.refresh()`: the cancel + join of the collector on the
   application scope from `releaseRestoreHold()` on Main — can it block
   Main, and can `firstLoad` ever stay incomplete so that `await()` hangs
   `onIncoming()` (there is a 2 s timeout on that path)?
4. The new English report `docs/reviews/2026-10-08-backup-release-fixes.md`
   and the README/user guide (`README.md`, `docs/USAGE.md`): any claim that
   the code does not back.
