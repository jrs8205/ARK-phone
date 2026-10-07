# External review brief — ARK calls go public, nine new languages (2026-10-07)

You are reviewing **four commits** in the ARK-phone repository (branch
`feature/voip-spike`, commits `ffbf7a1`, `790aea8`, `ec03f85`, `e65a3c1` on
top of `fe55679`, which is the 1.27 line plus two field fixes). Together they
become release **1.28**, the first public build that carries ARK internet
calls, and the first with more than two languages. Nothing here is a bug fix;
it is a scope change of the public release, and the maintainer wants
everything it touches checked before it ships.

```
git diff --stat fe55679..e65a3c1   # 63 files, +2949 / -124
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

Part A — code findings on commit 1 (R8, startup network use, permissions,
background execution, abuse), in the finding format above.

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
