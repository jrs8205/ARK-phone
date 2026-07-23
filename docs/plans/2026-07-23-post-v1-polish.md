# Post-v1 Polish Implementation Plan


**Goal:** Apply the eight post-v1 polish items carried forward from the v1 final review: mechanical cleanups, resource-level fixes, dial pad long-press `+`, and an InCallActivity stale-launch finish guard.

**Architecture:** All changes are small, localized edits to the existing single-module app. Two behavioral additions (long-press `+`, stale-launch guard) get TDD with Robolectric Compose tests following the existing `DialpadContentTest` pattern; the rest are behavior-preserving cleanups verified by the existing suite.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose, Material 3, Hilt, Robolectric + Compose UI test (unit test source set).

## Global Constraints

- Everything committed (code, comments, commit messages) must be in English. Finnish text may appear ONLY in `app/src/main/res/values-fi/` resources.
- No mentions of AI tools anywhere in the repo. Commit messages must NOT include any Co-Authored-By trailer.
- The app name is `ARK-phone` (hyphen, lowercase p) in any new user-facing text.
- Do not change build tool versions: Kotlin 2.3.21, KSP 2.3.10, Gradle wrapper 9.4.1, AGP 9.2.0 with built-in Kotlin (do NOT apply `org.jetbrains.kotlin.android`).
- Before running Gradle commands in Git Bash, run: `export ANDROID_SDK_ROOT="$LOCALAPPDATA/Android/Sdk"`.
- Test command: `./gradlew :app:testDebugUnitTest` (optionally with `--tests` filters).
- Theming invariant: on API 26–30 the app is always dark (custom dark scheme); on API 31+ it follows the system light/dark setting with dynamic colors. Window themes must match this exactly.

---

### Task 1: Mechanical cleanups (Gradle heap, Locale.ROOT, imports, conflate)

All four items are behavior-preserving. One commit per checklist section is fine; a single combined commit is also acceptable.

**Files:**
- Modify: `gradle.properties:1`
- Modify: `app/src/main/java/org/jarsi/arkphone/util/TimeFormat.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/util/TimeFormatTest.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/data/SystemCallLogRepository.kt:78`
- Modify: `app/src/main/java/org/jarsi/arkphone/data/SystemContactsRepository.kt:70`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/navigation/MainScreen.kt` (imports + `DefaultDialerBanner`)
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/dialpad/DialpadGrid.kt` (imports only)
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/contacts/ContactsScreen.kt` (imports only)

**Interfaces:**
- Consumes: existing `formatDuration(totalSeconds: Long): String`.
- Produces: no signature changes anywhere.

- [ ] **Step 1: Reduce Gradle heap**

In `gradle.properties` change the first line to:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
```

- [ ] **Step 2: Write the failing locale test**

Add to `app/src/test/java/org/jarsi/arkphone/util/TimeFormatTest.kt`:

```kotlin
@Test
fun formatsWithAsciiDigitsRegardlessOfDefaultLocale() {
    val original = Locale.getDefault()
    try {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("12:03", formatDuration(12 * 60 + 3))
        assertEquals("1:02:03", formatDuration(3600 + 2 * 60 + 3))
    } finally {
        Locale.setDefault(original)
    }
}
```

Add `import java.util.Locale` to the test file.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.util.TimeFormatTest'`
Expected: FAIL — `ar-EG` default locale renders `%d`/`%02d` with Eastern Arabic digits.

- [ ] **Step 4: Pin formatDuration to Locale.ROOT**

Replace `app/src/main/java/org/jarsi/arkphone/util/TimeFormat.kt` body:

```kotlin
package org.jarsi.arkphone.util

import java.util.Locale

fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.util.TimeFormatTest'`
Expected: PASS (all tests in the class).

- [ ] **Step 6: Add conflate() to both system repositories**

In BOTH `SystemCallLogRepository.kt` and `SystemContactsRepository.kt`, change the flow chain end (currently `.map { query() }.flowOn(ioDispatcher)`) to:

```kotlin
        }.conflate().map { query() }.flowOn(ioDispatcher)
```

Add `import kotlinx.coroutines.flow.conflate` to both files (alphabetical position among the existing `kotlinx.coroutines.flow.*` imports). Rationale: bursts of ContentObserver notifications must not queue redundant `query()` executions; conflate keeps only the latest pending tick.

- [ ] **Step 7: Import cleanups (no behavior change)**

1. `MainScreen.kt`: move `import androidx.compose.foundation.layout.Column` (currently line 23) up into alphabetical order, directly before `import androidx.compose.foundation.layout.Row`.
2. `MainScreen.kt`: add `import androidx.compose.material3.MaterialTheme` (alphabetical position, after `ListItem`-like M3 imports — concretely between `androidx.compose.material3.Icon` and `androidx.compose.material3.NavigationBar`) and in `DefaultDialerBanner` replace `androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer` with `MaterialTheme.colorScheme.secondaryContainer`.
3. `DialpadGrid.kt`: reorder the imports into a single alphabetical block:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
```

4. `ContactsScreen.kt`: swap the two lines so `import androidx.compose.material3.ListItem` comes before `import androidx.compose.material3.MaterialTheme`.

- [ ] **Step 8: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — cleanups are behavior-preserving.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Apply mechanical cleanups: 4g heap, Locale.ROOT durations, conflated observers, import order"
```

---

### Task 2: CallType.OTHER label and DayNight window theme

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-fi/strings.xml`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/recents/RecentsScreen.kt:85-93`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/recents/CallTypeLabelTest.kt` (create)
- Create: `app/src/main/res/values-v31/themes.xml`
- Create: `app/src/main/res/values-night-v31/themes.xml`
- Unchanged on purpose: `app/src/main/res/values/themes.xml` (dark parent stays — API 26–30 is always dark)

**Interfaces:**
- Produces: `internal fun callTypeLabelRes(type: CallType): Int` in `RecentsScreen.kt` (top-level, annotated `@StringRes`).

- [ ] **Step 1: Write the failing label test**

Create `app/src/test/java/org/jarsi/arkphone/ui/recents/CallTypeLabelTest.kt`:

```kotlin
package org.jarsi.arkphone.ui.recents

import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

class CallTypeLabelTest {

    @Test
    fun otherGetsGenericLabel() {
        assertEquals(R.string.call_type_other, callTypeLabelRes(CallType.OTHER))
    }

    @Test
    fun specificTypesKeepTheirLabels() {
        assertEquals(R.string.call_type_incoming, callTypeLabelRes(CallType.INCOMING))
        assertEquals(R.string.call_type_outgoing, callTypeLabelRes(CallType.OUTGOING))
        assertEquals(R.string.call_type_missed, callTypeLabelRes(CallType.MISSED))
        assertEquals(R.string.call_type_rejected, callTypeLabelRes(CallType.REJECTED))
    }
}
```

NOTE: if the `CallType` import path differs (check the existing import in `RecentsScreen.kt`), use that path.

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.ui.recents.CallTypeLabelTest'`
Expected: FAIL — unresolved `callTypeLabelRes` and `R.string.call_type_other`.

- [ ] **Step 3: Add the string resources**

`values/strings.xml`, after `call_type_rejected`:

```xml
    <string name="call_type_other">Call</string>
```

`values-fi/strings.xml`, after `call_type_rejected`:

```xml
    <string name="call_type_other">Puhelu</string>
```

- [ ] **Step 4: Extract the label mapping and use call_type_other**

In `RecentsScreen.kt`, replace the inline `when` inside `RecentsRow` with a call to a new top-level function, and add the function (plus `import androidx.annotation.StringRes`):

```kotlin
@StringRes
internal fun callTypeLabelRes(type: CallType): Int = when (type) {
    CallType.INCOMING -> R.string.call_type_incoming
    CallType.OUTGOING -> R.string.call_type_outgoing
    CallType.MISSED -> R.string.call_type_missed
    CallType.REJECTED -> R.string.call_type_rejected
    CallType.OTHER -> R.string.call_type_other
}
```

In `RecentsRow`: `val typeLabel = stringResource(callTypeLabelRes(entry.type))`

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.ui.recents.CallTypeLabelTest'`
Expected: PASS.

- [ ] **Step 6: Add DayNight window themes for API 31+**

Create `app/src/main/res/values-v31/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ArkPhone" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

Create `app/src/main/res/values-night-v31/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ArkPhone" parent="android:Theme.Material.NoActionBar" />
</resources>
```

Do NOT touch `values/themes.xml`: below API 31 the Compose theme is always dark, so the dark window theme is correct there in both system modes. This fixes the dark window flash before first Compose frame for light-mode users on API 31+ only.

- [ ] **Step 7: Build resources and run the suite**

Run: `./gradlew :app:processDebugResources :app:testDebugUnitTest`
Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add generic OTHER call label and follow system theme in the API 31+ window theme"
```

---

### Task 3: Long-press + on dial pad 0 key

**Files:**
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/dialpad/DialpadGrid.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/dialpad/DialpadGridTest.kt` (create)

**Interfaces:**
- Consumes: `DialpadGrid(onKey: (Char) -> Unit)` — public signature must NOT change (`DialpadScreen.kt:98` calls it).
- Produces: long-pressing the `0` key emits `'+'` through the same `onKey` callback.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/org/jarsi/arkphone/ui/dialpad/DialpadGridTest.kt` (same harness as the existing `DialpadContentTest`):

```kotlin
package org.jarsi.arkphone.ui.dialpad

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DialpadGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressOnZeroEmitsPlus() {
        val pressed = mutableListOf<Char>()
        composeRule.setContent { DialpadGrid(onKey = { pressed.add(it) }) }
        composeRule.onNodeWithText("0").performTouchInput { longClick() }
        assertEquals(listOf('+'), pressed)
    }

    @Test
    fun tapOnZeroStillEmitsZero() {
        val pressed = mutableListOf<Char>()
        composeRule.setContent { DialpadGrid(onKey = { pressed.add(it) }) }
        composeRule.onNodeWithText("0").performClick()
        assertEquals(listOf('0'), pressed)
    }

    @Test
    fun longPressOnOtherKeyEmitsNothing() {
        val pressed = mutableListOf<Char>()
        composeRule.setContent { DialpadGrid(onKey = { pressed.add(it) }) }
        composeRule.onNodeWithText("5").performTouchInput { longClick() }
        assertEquals(emptyList<Char>(), pressed)
    }
}
```

- [ ] **Step 2: Run the tests to verify the long-press cases fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.ui.dialpad.DialpadGridTest'`
Expected: `longPressOnZeroEmitsPlus` FAILS (long press falls through to a plain click or emits nothing); `tapOnZeroStillEmitsZero` passes.

- [ ] **Step 3: Implement long-press on the 0 key**

In `DialpadGrid.kt`, pass a long-press action only for `'0'` and switch `DialpadKey` from `Surface(onClick)` to `combinedClickable`:

```kotlin
@Composable
fun DialpadGrid(onKey: (Char) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        dialpadRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (digit, letters) ->
                    DialpadKey(
                        digit = digit,
                        letters = letters,
                        onKey = onKey,
                        onLongPress = if (digit == '0') {
                            { onKey('+') }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialpadKey(
    digit: Char,
    letters: String,
    onKey: (Char) -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = { onKey(digit) },
                onLongClick = onLongPress,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = digit.toString(), style = MaterialTheme.typography.headlineSmall)
                if (letters.isNotEmpty()) {
                    Text(
                        text = letters,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
```

New imports (alphabetical order): `androidx.compose.foundation.combinedClickable`, `androidx.compose.ui.draw.clip`. If the compiler requires it, add `@OptIn(ExperimentalFoundationApi::class)` on `DialpadKey` with `import androidx.compose.foundation.ExperimentalFoundationApi`; if the compiler reports the opt-in as unnecessary, omit it.

- [ ] **Step 4: Run the tests to verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.ui.dialpad.*'`
Expected: PASS, including the pre-existing `DialpadContentTest.keysReportPresses` (taps still work through `combinedClickable`).

- [ ] **Step 5: Run the full unit suite and commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

```bash
git add -A
git commit -m "Support long-press plus on the dial pad zero key"
```

---

### Task 4: InCallActivity stale-launch grace-period finish

Fixes the stale-notification race: if `InCallActivity` is launched (e.g. from a lingering notification) when no call exists anymore, it currently stays open forever because the existing guard only finishes after it has *seen* a call.

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/ui/incall/InCallFinishGuard.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/incall/InCallActivity.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/incall/InCallFinishGuardTest.kt` (create)

**Interfaces:**
- Produces: `@Composable fun InCallFinishGuard(hasCall: Boolean, graceMillis: Long = STALE_LAUNCH_GRACE_MILLIS, onFinish: () -> Unit)` and `const val STALE_LAUNCH_GRACE_MILLIS = 2_000L`, both in `org.jarsi.arkphone.ui.incall`.
- Consumes: `InCallActivity` renders it with `hasCall = uiState.call != null` and `onFinish = ::finish`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/org/jarsi/arkphone/ui/incall/InCallFinishGuardTest.kt`:

```kotlin
package org.jarsi.arkphone.ui.incall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InCallFinishGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun finishesWhenNoCallArrivesWithinGrace() {
        var finishCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = false, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(1_900L)
        assertEquals(0, finishCount)
        composeRule.mainClock.advanceTimeBy(200L)
        assertEquals(1, finishCount)
    }

    @Test
    fun doesNotFinishWhileCallIsPresent() {
        var finishCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = true, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(5_000L)
        assertEquals(0, finishCount)
    }

    @Test
    fun finishesWhenASeenCallEnds() {
        var finishCount = 0
        var hasCall by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = hasCall, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        hasCall = false
        composeRule.mainClock.advanceTimeBy(500L)
        assertEquals(1, finishCount)
    }

    @Test
    fun callArrivingWithinGraceSuppressesTheGraceFinish() {
        var finishCount = 0
        var hasCall by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = hasCall, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        hasCall = true
        composeRule.mainClock.advanceTimeBy(5_000L)
        assertEquals(0, finishCount)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.ui.incall.InCallFinishGuardTest'`
Expected: FAIL — `InCallFinishGuard` does not exist yet.

- [ ] **Step 3: Implement InCallFinishGuard**

Create `app/src/main/java/org/jarsi/arkphone/ui/incall/InCallFinishGuard.kt`:

```kotlin
package org.jarsi.arkphone.ui.incall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

const val STALE_LAUNCH_GRACE_MILLIS = 2_000L

/**
 * Finishes the in-call screen when it has nothing to show: immediately once a
 * previously seen call ends, or after [graceMillis] if the screen was launched
 * without any call ever appearing (stale notification race).
 */
@Composable
fun InCallFinishGuard(
    hasCall: Boolean,
    graceMillis: Long = STALE_LAUNCH_GRACE_MILLIS,
    onFinish: () -> Unit,
) {
    var sawCall by remember { mutableStateOf(false) }
    LaunchedEffect(hasCall) {
        if (hasCall) {
            sawCall = true
        } else if (sawCall) {
            onFinish()
        }
    }
    LaunchedEffect(Unit) {
        delay(graceMillis)
        if (!sawCall) {
            onFinish()
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.jarsi.arkphone.ui.incall.InCallFinishGuardTest'`
Expected: PASS (compose-test delay skipping honors `mainClock.advanceTimeBy`).

- [ ] **Step 5: Use the guard in InCallActivity**

Replace the inline guard block in `InCallActivity.kt` `setContent` with:

```kotlin
        setContent {
            ArkPhoneTheme {
                val viewModel: InCallViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                InCallFinishGuard(hasCall = uiState.call != null, onFinish = ::finish)
                CallScreen(uiState = uiState, actions = viewModel)
            }
        }
```

Remove the now-unused imports from `InCallActivity.kt`: `LaunchedEffect`, `mutableStateOf`, `remember`, `setValue` (keep `getValue` — the `by` delegate on `uiState` still needs it).

- [ ] **Step 6: Run the full unit suite and commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

```bash
git add -A
git commit -m "Finish stale in-call screen after a short grace period"
```
