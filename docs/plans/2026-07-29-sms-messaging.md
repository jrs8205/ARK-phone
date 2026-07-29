# SMS Messaging Implementation Plan


**Goal:** ARK-phone becomes the device's default SMS app: conversations, thread view, send/receive SMS with delivery reports and SIM selection, quick reply from notifications, blocked-number silencing, MMS receive/display and single-image MMS send.

**Architecture:** The system Telephony provider (`Telephony.Sms`, `Telephony.Mms`, threads) is the single source of truth — the app keeps no message store. UI reads through `observedQueryFlow` (the pattern the call log already uses). Send/receive plumbing lives in a new `messaging/` package behind small fun interfaces so everything is unit-testable. Spec: `docs/specs/2026-07-29-sms-messaging-design.md`.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Hilt, Preferences DataStore (existing), Coil (existing, MMS images), Robolectric + JUnit + Turbine for tests. No new Gradle dependencies.

## Global Constraints

- Everything committed is English-only; Finnish only in `app/src/main/res/values-fi/strings.xml`. No AI-tool mentions anywhere; commits have NO Co-Authored-By trailer.
- Gates after every task: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug` — lint runs with `warningsAsErrors`, any new warning fails the build.
- minSdk 26, targetSdk 36. Guard APIs above 26 explicitly.
- TDD: write the failing test first, watch it fail, then implement.
- Any test touching `android.util.Log` or Android classes needs Robolectric; pure logic stays plain JUnit.
- Every new user-visible string goes to BOTH `values/strings.xml` (English) and `values-fi/strings.xml` (Finnish).
- Reference reading (never committed): `reference/fossify-messages` — create once with `git clone --depth 1 https://github.com/FossifyOrg/Messages.git reference/fossify-messages` (the `reference/` folder is git-ignored).
- PowerShell trap: never pipe gradle through `| Select-Object -First N` — it kills the daemon mid-run. Filter with `Out-String -Stream` instead.

---

### Task 1: Messaging models and pure provider-row mapping

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/data/model/Message.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/data/model/Conversation.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/data/MessageMapping.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/MessageMappingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Message`, `MessageStatus`, `MmsAttachment`, `Conversation` data classes and `internal fun smsStatusFrom(type: Int, status: Int): MessageStatus`, `internal fun mmsTimestampMillis(providerDateSeconds: Long): Long`. Later tasks build on these exact names.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.jarsi.arkphone.data

import android.provider.Telephony
import org.jarsi.arkphone.data.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMappingTest {

    @Test
    fun `outbox and queued rows are sending`() {
        assertEquals(
            MessageStatus.SENDING,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.STATUS_NONE),
        )
        assertEquals(
            MessageStatus.SENDING,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `sent row without delivery report is sent`() {
        assertEquals(
            MessageStatus.SENT,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_PENDING),
        )
        assertEquals(
            MessageStatus.SENT,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `sent row with completed delivery report is delivered`() {
        assertEquals(
            MessageStatus.DELIVERED,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_COMPLETE),
        )
    }

    @Test
    fun `failed row is failed`() {
        assertEquals(
            MessageStatus.FAILED,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `incoming row has no status`() {
        assertEquals(
            MessageStatus.NONE,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_INBOX, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `mms provider dates are seconds`() {
        assertEquals(1_722_000_000_000L, mmsTimestampMillis(1_722_000_000L))
    }
}
```

Note: `Telephony.Sms.*` constants make this a Robolectric-free test only if the constants resolve — they are plain ints in the android.jar stub, so plain JUnit works with `testOptions.unitTests.isReturnDefaultValues` unset; if the stub throws, annotate the class with `@RunWith(RobolectricTestRunner::class)` instead. Try plain JUnit first.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.MessageMappingTest" 2>&1 | Out-String -Stream`
Expected: FAIL — `smsStatusFrom` unresolved.

- [ ] **Step 3: Write the models and mapping**

`data/model/Message.kt`:

```kotlin
package org.jarsi.arkphone.data.model

enum class MessageStatus { NONE, SENDING, SENT, DELIVERED, FAILED }

/** An image (or other media) part of an MMS, addressed by its part URI. */
data class MmsAttachment(
    val partUri: String,
    val mimeType: String,
)

data class Message(
    val id: Long,
    val threadId: Long,
    /** True for MMS rows; ids overlap between the sms and mms tables. */
    val isMms: Boolean,
    val address: String,
    val body: String?,
    val timestampMillis: Long,
    val incoming: Boolean,
    val status: MessageStatus,
    val subscriptionId: Int,
    val attachments: List<MmsAttachment> = emptyList(),
    /** True for an MMS notification we failed to download; enables tap-to-retry. */
    val pendingDownload: Boolean = false,
)
```

`data/model/Conversation.kt`:

```kotlin
package org.jarsi.arkphone.data.model

data class Conversation(
    val threadId: Long,
    /** Raw addresses of every participant; size > 1 means a group thread. */
    val addresses: List<String>,
    val snippet: String?,
    val timestampMillis: Long,
    val unread: Boolean,
)
```

`data/MessageMapping.kt`:

```kotlin
package org.jarsi.arkphone.data

import android.provider.Telephony
import org.jarsi.arkphone.data.model.MessageStatus

/** Maps an sms table row's TYPE and STATUS columns to one UI status. */
internal fun smsStatusFrom(type: Int, status: Int): MessageStatus = when (type) {
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_QUEUED,
    -> MessageStatus.SENDING
    Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageStatus.FAILED
    Telephony.Sms.MESSAGE_TYPE_SENT ->
        if (status == Telephony.Sms.STATUS_COMPLETE) MessageStatus.DELIVERED else MessageStatus.SENT
    else -> MessageStatus.NONE
}

/** The mms table stores DATE in seconds where sms stores milliseconds. */
internal fun mmsTimestampMillis(providerDateSeconds: Long): Long = providerDateSeconds * 1000
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.MessageMappingTest" 2>&1 | Out-String -Stream`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/jarsi/arkphone/data/model/Message.kt app/src/main/java/org/jarsi/arkphone/data/model/Conversation.kt app/src/main/java/org/jarsi/arkphone/data/MessageMapping.kt app/src/test/java/org/jarsi/arkphone/data/MessageMappingTest.kt
git commit -m "Add messaging models and sms status mapping"
```

---

### Task 2: MessagesRepository interface, conversations query, fake provider test infra

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/data/MessagesRepository.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/data/SystemMessagesRepository.kt`
- Create: `app/src/test/java/org/jarsi/arkphone/data/FakeTelephonyProvider.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/SystemMessagesRepositoryTest.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt` (bind the repository)

**Interfaces:**
- Consumes: `observedQueryFlow` (`data/ObservedQueryFlow.kt`), `PermissionChecker.has(permission: String): Boolean`, `@IoDispatcher CoroutineDispatcher`, `Conversation` from Task 1.
- Produces:

```kotlin
interface MessagesRepository {
    fun conversations(): Flow<List<Conversation>>
    fun messages(threadId: Long): Flow<List<Message>>          // Task 3
    fun refresh()
    suspend fun markThreadRead(threadId: Long)                  // Task 3
    suspend fun deleteThread(threadId: Long): Boolean           // Task 3
    suspend fun threadIdsMatchingBody(query: String): Set<Long> // Task 5
}
```

Task 2 implements `conversations()` and `refresh()`; the rest throw `UnsupportedOperationException` placeholders REMOVED in their own tasks (Tasks 3 and 5) — Task 2's commit may only contain TODO-free code, so implement them as simple immediate versions here instead: `messages` returns `flowOf(emptyList())`, `markThreadRead`/`deleteThread` return without effect (`false`), `threadIdsMatchingBody` returns `emptySet()`. Tasks 3/5 replace these bodies test-first.

**Provider facts (verify against `reference/fossify-messages` while implementing):**
- Conversation list: `content://mms-sms/conversations?simple=true`, projection `_id, date, message_count, recipient_ids, snippet, read`. `recipient_ids` is a space-separated id list.
- Address per recipient id: `content://mms-sms/canonical-address/{id}`, single string column.
- Requires `READ_SMS`.

- [ ] **Step 1: Write the fake provider and the failing test**

`FakeTelephonyProvider.kt` — one in-memory `ContentProvider` registered for authorities `mms-sms`, `sms`, `mms` via Robolectric. It backs all repository tests in this plan:

```kotlin
package org.jarsi.arkphone.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Minimal in-memory stand-in for the telephony providers. Tests seed
 *  [conversationRows], [smsRows] and [mmsRows] directly; queries answer from
 *  them by URI shape. Only the columns the app reads are simulated. */
class FakeTelephonyProvider : ContentProvider() {

    // Column order must match the projections SystemMessagesRepository uses.
    val conversationRows = mutableListOf<Array<Any?>>() // _id, date, message_count, recipient_ids, snippet, read
    val canonicalAddresses = mutableMapOf<Long, String>()
    val smsRows = mutableListOf<ContentValues>()
    val mmsRows = mutableListOf<ContentValues>()
    val deletedUris = mutableListOf<Uri>()
    val updatedUris = mutableListOf<Pair<Uri, ContentValues>>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor {
        val path = uri.toString()
        return when {
            path.startsWith("content://mms-sms/conversations") -> {
                val cursor = MatrixCursor(
                    arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                )
                conversationRows.forEach(cursor::addRow)
                cursor
            }
            path.startsWith("content://mms-sms/canonical-address/") -> {
                val id = uri.lastPathSegment!!.toLong()
                MatrixCursor(arrayOf("address")).apply {
                    canonicalAddresses[id]?.let { addRow(arrayOf(it)) }
                }
            }
            else -> MatrixCursor(projection ?: emptyArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
        deletedUris += uri
        return 1
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int {
        updatedUris += uri to (values ?: ContentValues())
        return 1
    }
    override fun getType(uri: Uri): String? = null
}
```

`SystemMessagesRepositoryTest.kt`:

```kotlin
package org.jarsi.arkphone.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.util.PermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController

@RunWith(RobolectricTestRunner::class)
class SystemMessagesRepositoryTest {

    private lateinit var provider: FakeTelephonyProvider
    private lateinit var repository: SystemMessagesRepository
    private var hasReadSms = true

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        // The same instance must answer for the sms and mms authorities too.
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
        val context = ApplicationProvider.getApplicationContext<Application>()
        repository = SystemMessagesRepository(
            context = context,
            permissionChecker = PermissionChecker { hasReadSms },
            ioDispatcher = StandardTestDispatcher(),
        )
    }

    @Test
    fun `conversations resolve recipient addresses and unread state`() = runTest {
        provider.canonicalAddresses[7L] = "+358441234567"
        provider.conversationRows += arrayOf<Any?>(3L, 1_722_000_000_000L, 5, "7", "Hei!", 0)
        val conversations = repository.conversations().first()
        assertEquals(1, conversations.size)
        with(conversations.single()) {
            assertEquals(3L, threadId)
            assertEquals(listOf("+358441234567"), addresses)
            assertEquals("Hei!", snippet)
            assertTrue(unread)
        }
    }

    @Test
    fun `group thread carries every address`() = runTest {
        provider.canonicalAddresses[1L] = "+358401111111"
        provider.canonicalAddresses[2L] = "+358402222222"
        provider.conversationRows += arrayOf<Any?>(9L, 1L, 2, "1 2", null, 1)
        assertEquals(
            listOf("+358401111111", "+358402222222"),
            repository.conversations().first().single().addresses,
        )
    }

    @Test
    fun `no permission yields empty list`() = runTest {
        hasReadSms = false
        provider.conversationRows += arrayOf<Any?>(3L, 1L, 1, "7", "x", 0)
        assertTrue(repository.conversations().first().isEmpty())
    }
}
```

Note the `PermissionChecker { hasReadSms }` lambda — `PermissionChecker` is an existing fun interface taking the permission string; adapt to its actual shape (`PermissionChecker { _ -> hasReadSms }`) if it takes an argument. If registering one instance for three authorities proves awkward in Robolectric, register three controller instances sharing static storage (make the row lists `companion object` members) — adjust in Step 3, keep the assertions identical.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.SystemMessagesRepositoryTest" 2>&1 | Out-String -Stream`
Expected: FAIL — `SystemMessagesRepository` unresolved.

- [ ] **Step 3: Implement the repository**

`data/MessagesRepository.kt` with the full interface from the Interfaces block (KDoc each method). `data/SystemMessagesRepository.kt` mirrors `SystemCallLogRepository` structurally:

```kotlin
package org.jarsi.arkphone.data

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemMessagesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MessagesRepository {

    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val conversationsUri: Uri = Uri.parse("content://mms-sms/conversations?simple=true")

    override fun refresh() {
        refreshSignal.tryEmit(Unit)
    }

    override fun conversations(): Flow<List<Conversation>> {
        val resolver = context.contentResolver
        fun query(): List<Conversation> {
            if (!permissionChecker.has(Manifest.permission.READ_SMS)) return emptyList()
            val conversations = mutableListOf<Conversation>()
            resolver.query(
                conversationsUri,
                arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                null, null, "date DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val recipientIds = cursor.getString(3).orEmpty()
                        .split(' ').mapNotNull { it.toLongOrNull() }
                    conversations += Conversation(
                        threadId = cursor.getLong(0),
                        addresses = recipientIds.mapNotNull(::canonicalAddress),
                        snippet = cursor.getString(4)?.takeIf { it.isNotBlank() },
                        timestampMillis = cursor.getLong(1),
                        unread = cursor.getInt(5) == 0,
                    )
                }
            }
            return conversations
        }
        var observer: ContentObserver? = null
        return observedQueryFlow(
            hasPermission = { permissionChecker.has(Manifest.permission.READ_SMS) },
            registerObserver = { notifyChange ->
                val registered = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = notifyChange()
                }
                observer = registered
                resolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, registered)
            },
            unregisterObserver = { observer?.let(resolver::unregisterContentObserver) },
            refreshSignal = refreshSignal,
            query = ::query,
        ).flowOn(ioDispatcher)
    }

    private fun canonicalAddress(recipientId: Long): String? =
        context.contentResolver.query(
            Uri.parse("content://mms-sms/canonical-address/$recipientId"),
            null, null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    // Replaced test-first in Task 3.
    override fun messages(threadId: Long): Flow<List<Message>> = flowOf(emptyList())
    override suspend fun markThreadRead(threadId: Long) = Unit
    override suspend fun deleteThread(threadId: Long): Boolean = false

    // Replaced test-first in Task 5.
    override suspend fun threadIdsMatchingBody(query: String): Set<Long> = emptySet()
}
```

Bind in `AppModule.kt` next to the other `@Binds`:

```kotlin
@Binds
@Singleton
abstract fun bindMessagesRepository(impl: SystemMessagesRepository): MessagesRepository
```

(with imports `org.jarsi.arkphone.data.MessagesRepository`, `org.jarsi.arkphone.data.SystemMessagesRepository`).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.SystemMessagesRepositoryTest" 2>&1 | Out-String -Stream`
Expected: PASS

- [ ] **Step 5: Run the full gate and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug 2>&1 | Out-String -Stream`

```bash
git add app/src/main/java/org/jarsi/arkphone/data/MessagesRepository.kt app/src/main/java/org/jarsi/arkphone/data/SystemMessagesRepository.kt app/src/test/java/org/jarsi/arkphone/data/FakeTelephonyProvider.kt app/src/test/java/org/jarsi/arkphone/data/SystemMessagesRepositoryTest.kt app/src/main/java/org/jarsi/arkphone/di/AppModule.kt
git commit -m "Read the conversation list from the telephony provider"
```

---

### Task 3: Thread messages, mark-read and delete

**Files:**
- Modify: `app/src/main/java/org/jarsi/arkphone/data/SystemMessagesRepository.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/SystemMessagesRepositoryTest.kt` (extend)

**Interfaces:**
- Consumes: Task 1 models, Task 2 repository skeleton and `FakeTelephonyProvider`.
- Produces: working `messages(threadId): Flow<List<Message>>` (SMS rows only — MMS rows join in Task 13), `markThreadRead(threadId)`, `deleteThread(threadId): Boolean`.

**Provider facts:**
- SMS in a thread: `Telephony.Sms.CONTENT_URI`, selection `thread_id = ?`, projection `_id, thread_id, address, body, date, type, status, read, sub_id`, sort `date ASC`.
- Mark read: update `Telephony.Sms.CONTENT_URI` set `read=1, seen=1` where `thread_id = ? AND read = 0`. (MMS mark-read joins in Task 13 with the same pattern on `Telephony.Mms.CONTENT_URI`.)
- Delete thread: delete `content://mms-sms/conversations/{threadId}`.
- Writes require being the default SMS app; wrap in `runCatching`, return false on failure.

- [ ] **Step 1: Extend the fake provider and write failing tests**

Extend `FakeTelephonyProvider.query` with an sms-table branch: when `uri == Telephony.Sms.CONTENT_URI`, build a `MatrixCursor` with columns `_id, thread_id, address, body, date, type, status, read, sub_id` from `smsRows` (a `ContentValues` list — filter by the `thread_id` selection arg). Add tests:

```kotlin
@Test
fun `thread messages map status and direction`() = runTest {
    provider.smsRows += smsRow(
        id = 1, threadId = 3, address = "+358441234567", body = "Moro",
        date = 1000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status = Telephony.Sms.STATUS_NONE, subId = 1,
    )
    provider.smsRows += smsRow(
        id = 2, threadId = 3, address = "+358441234567", body = "Takaisin",
        date = 2000L, type = Telephony.Sms.MESSAGE_TYPE_SENT,
        status = Telephony.Sms.STATUS_COMPLETE, subId = 1,
    )
    val messages = repository.messages(3L).first()
    assertEquals(listOf(false, true).map { it }, messages.map { !it.incoming })
    assertEquals(MessageStatus.DELIVERED, messages[1].status)
}

@Test
fun `mark read updates only unread rows of the thread`() = runTest {
    repository.markThreadRead(3L)
    val (uri, values) = provider.updatedUris.single()
    assertEquals(Telephony.Sms.CONTENT_URI, uri)
    assertEquals(1, values.getAsInteger(Telephony.Sms.READ))
}

@Test
fun `delete thread targets the conversations uri`() = runTest {
    assertTrue(repository.deleteThread(3L))
    assertTrue(provider.deletedUris.single().toString().endsWith("/conversations/3"))
}
```

with a local `smsRow(...)` helper building the `ContentValues`. `incoming` = `type == MESSAGE_TYPE_INBOX`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.SystemMessagesRepositoryTest" 2>&1 | Out-String -Stream`
Expected: the three new tests FAIL (empty flow / no-op stubs).

- [ ] **Step 3: Implement**

Replace the Task 2 stubs. `messages(threadId)` follows the same `observedQueryFlow` shape as `conversations()` (observer on `Telephony.MmsSms.CONTENT_URI`), querying the sms table as documented above and mapping rows with `smsStatusFrom`. `markThreadRead` and `deleteThread` run `withContext(ioDispatcher)` inside `runCatching`.

- [ ] **Step 4: Run tests to verify they pass**

Same command. Expected: PASS.

- [ ] **Step 5: Run gates and commit**

```bash
git add app/src/main/java/org/jarsi/arkphone/data/SystemMessagesRepository.kt app/src/test/java/org/jarsi/arkphone/data/FakeTelephonyProvider.kt app/src/test/java/org/jarsi/arkphone/data/SystemMessagesRepositoryTest.kt
git commit -m "Read, mark read and delete message threads"
```

---

### Task 4: Messages tab — conversation list UI

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/ui/messages/MessagesViewModel.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/ui/messages/MessagesScreen.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/navigation/MainScreen.kt` (fourth tab)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fi/strings.xml`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/messages/MessagesViewModelTest.kt`, `app/src/test/java/org/jarsi/arkphone/ui/messages/MessagesScreenTest.kt`

**Interfaces:**
- Consumes: `MessagesRepository.conversations()/refresh()`, `ContactsRepository.contacts(): Flow<List<Contact>>` (Contact has `displayName`, `photoUri`, numbers), `sameCaller(a, b)` from `util/PhoneNumbers.kt`, `SearchField`, `RowCard`, `transparentListItemColors`, `ContactAvatar(displayName, photoUri, …)`, `clickableListItem` modifier from `ui/components/ListModifiers.kt`.
- Produces: `MessagesViewModel` with `data class MessagesUiState(val conversations: List<ConversationItem>, val query: String, val hasReadSmsPermission: Boolean)` and `data class ConversationItem(val conversation: Conversation, val title: String, val photoUri: String?, val isGroup: Boolean)`; `fun onQueryChange(query: String)`; `fun refreshPermissionState()`. `MessagesScreen(onOpenThread: (Long) -> Unit, onNewMessage: () -> Unit, onRequestPermission: () -> Unit)` composable. Pure fn `internal fun conversationTitle(addresses: List<String>, contacts: List<Contact>): String` (single address → contact name via `sameCaller` else the address; group → names/numbers joined with ", ").

Name matching in this task filters by title/address only; body search lands in Task 5.

- [ ] **Step 1: Write failing ViewModel test** — plain JUnit + Turbine with fake repositories (follow the existing `RecentsViewModel` test style): seed two conversations + one contact, assert `title` resolves the contact name, assert `onQueryChange("mat")` filters to the matching conversation, assert group flag when addresses.size > 1.
- [ ] **Step 2: Run it, verify failure.**
- [ ] **Step 3: Implement ViewModel** — combine `conversations()` + `contacts()` + query `MutableStateFlow` into `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`. Remember the Turbine trap: `stateIn(WhileSubscribed)` tests need `skipItems(1)`.
- [ ] **Step 4: Write failing Compose test** (Robolectric): conversation rows render title + snippet; unread row shows the dot (`testTag("unread")`); tapping a row invokes `onOpenThread(threadId)`.
- [ ] **Step 5: Implement `MessagesScreen`** — `SearchField` on top, `LazyColumn` of `RowCard` rows (`ListItem` with `ContactAvatar`, bold title + snippet when unread, timestamp via existing `TimeFormat` helpers), pencil `FloatingActionButton` (`Icons.Filled.Edit`) bottom-end for `onNewMessage`, a permission-request card when `!hasReadSmsPermission` (follow the pattern the SIM info page uses). Long-press copies the single address (skip for groups).
- [ ] **Step 6: Add the tab** — `MainTab.MESSAGES` between KEYPAD and CONTACTS; `NavigationBarItem` with `Icons.AutoMirrored.Filled.Message`, label `R.string.tab_messages`; `MainScreen` gains `onOpenThread`/`onNewMessage` parameters passed from `MainActivity`. Strings: `tab_messages` = "Messages" / "Viestit", `messages_search_placeholder` = "Search messages" / "Hae viesteistä", `messages_permission_rationale` = "To show your messages, allow SMS access." / "Salli tekstiviestien käyttö, jotta viestisi voidaan näyttää.", `messages_new` = "New message" / "Uusi viesti".
- [ ] **Step 7: Run the full gate** — `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug 2>&1 | Out-String -Stream`.
- [ ] **Step 8: Commit** — `git commit -m "Add the Messages tab with the conversation list"` (add all files from this task's Files block).

---

### Task 5: Message body search

**Files:**
- Modify: `app/src/main/java/org/jarsi/arkphone/data/SystemMessagesRepository.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/messages/MessagesViewModel.kt`
- Test: extend both test files

**Interfaces:**
- Consumes: Task 2/4 output.
- Produces: `threadIdsMatchingBody(query: String): Set<Long>` — sms table query `body LIKE ? ESCAPE '\'` with `%escaped%`, projection `thread_id`, distinct via Set. ViewModel: non-blank query shows conversations whose title/address matches OR whose threadId is in the body-match set (query runs in a `LaunchedEffect`-free way: `queryFlow.mapLatest { repository.threadIdsMatchingBody(it) }` combined in).

- [ ] **Step 1: Failing repository test** — seed sms rows in two threads, assert `threadIdsMatchingBody("mor")` returns only the matching thread; assert `%`/`_` in the query are escaped (seed body "100%" and query "0%").
- [ ] **Step 2: Verify failure.** — same gradle test command as Task 3.
- [ ] **Step 3: Implement** — escape `\`, `%`, `_` with a small pure `internal fun escapeLikeQuery(raw: String): String` (unit-test it in the same file run).
- [ ] **Step 4: Failing ViewModel test** — query hitting only a body still surfaces that conversation.
- [ ] **Step 5: Implement ViewModel wiring; run tests to green.**
- [ ] **Step 6: Gates + commit** — `git commit -m "Search conversations by message text"`.

---

### Task 6: Conversation screen (read-only first)

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/ui/conversation/ConversationActivity.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/ui/conversation/ConversationViewModel.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/ui/conversation/ConversationScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml` (activity, `android:exported="false"` for now — SENDTO filters arrive in Task 11)
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/messages/MessagesScreen.kt` wiring (MainActivity opens the activity)
- Modify: strings (en + fi)
- Test: `app/src/test/java/org/jarsi/arkphone/ui/conversation/ConversationViewModelTest.kt`, `.../ConversationScreenTest.kt`

**Interfaces:**
- Consumes: `MessagesRepository.messages/markThreadRead/deleteThread`, `ContactsRepository`, `BlockedNumbersRepository.block/unblock/isBlocked`, `PhoneCaller.placeCall(number)`, `ContactAvatar`, `TimeFormat`.
- Produces: `ConversationActivity.intent(context: Context, threadId: Long): Intent` (companion, `EXTRA_THREAD_ID = "org.jarsi.arkphone.extra.THREAD_ID"`); `ConversationUiState(val messages: List<Message>, val title: String, val photoUri: String?, val address: String?, val isGroup: Boolean, val blocked: Boolean)`; pure `internal fun dateSeparators(messages: List<Message>): List<ConversationRow>` where `sealed interface ConversationRow` = `DaySeparator(epochMillis)` / `MessageRow(message)`.

- [ ] **Step 1: Failing pure test for `dateSeparators`** — messages across two days produce separator/message/separator/message; same-day runs get one separator.
- [ ] **Step 2: Verify failure; implement; verify pass.**
- [ ] **Step 3: Failing ViewModel test** — opening thread marks it read exactly once; title resolves contact name; `blocked` reflects `BlockedNumbersRepository.isBlocked`.
- [ ] **Step 4: Implement ViewModel; verify pass.**
- [ ] **Step 5: Failing Compose test** — incoming bubble aligned start, outgoing end (testTags `bubble_in`/`bubble_out`); failed outgoing shows retry hint text; day separator visible.
- [ ] **Step 6: Implement screen** — `LazyColumn` (reverseLayout = false, scroll to bottom on open), bubbles: `Surface` with `RoundedCornerShape(18.dp)`, incoming `surfaceContainerHigh`, outgoing `primaryContainer`, max width ~0.8 of row; status line under the newest outgoing message (strings `message_status_sending/sent/delivered/failed_retry` en+fi); top bar `TopAppBar` with `ContactAvatar` + title (tap → `ContactCardActivity` when a contact match exists), call icon → `PhoneCaller.placeCall`, overflow menu: delete conversation (confirm `AlertDialog`), block/unblock number (single-address threads). The composer row is added in Task 7 — this task renders messages only.
- [ ] **Step 7: Manifest + wiring; gates; commit** — `git commit -m "Add the conversation screen"`.

---

### Task 7: SMS sending with delivery reports

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/SmsSender.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/AndroidSmsSender.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/SmsSendStatusReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml` (receiver, not exported)
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt` (bind)
- Modify: `ConversationViewModel`/`ConversationScreen` (composer + retry)
- Test: `app/src/test/java/org/jarsi/arkphone/messaging/AndroidSmsSenderTest.kt` (Robolectric, `ShadowSmsManager`), receiver test, ViewModel test extension

**Interfaces:**
- Consumes: `MessagesRepository`, Task 6 UI.
- Produces:

```kotlin
/** Sends one text to one recipient and records it in the provider. */
fun interface SmsSender {
    /** Returns the provider row URI, or null when the send could not start. */
    suspend fun send(address: String, body: String, subscriptionId: Int): Uri?
}
```

`AndroidSmsSender` (constructor-injected `@ApplicationContext context`, `@IoDispatcher dispatcher`): inserts the row FIRST — `ContentValues`: `address`, `body`, `date = System.currentTimeMillis()`, `read = 1`, `type = Telephony.Sms.MESSAGE_TYPE_OUTBOX`, `sub_id`, `thread_id = Telephony.Threads.getOrCreateThreadId(context, address)` — into `Telephony.Sms.CONTENT_URI`, then `SmsManager` (API 31+: `context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)`; below: `SmsManager.getSmsManagerForSubscriptionId(subId)` — wrap the deprecation in one place with `@Suppress("DEPRECATION")`), `divideMessage(body)`, `sendMultipartTextMessage(address, null, parts, sentIntents, deliveryIntents)`. Only the LAST part's PendingIntents carry the row URI (others null) — one status transition per message. PendingIntents target `SmsSendStatusReceiver` with actions `org.jarsi.arkphone.action.SMS_SENT` / `org.jarsi.arkphone.action.SMS_DELIVERED`, `data = rowUri`, flags `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE or FLAG_ONE_SHOT`, requestCode `rowUri.hashCode()`.

`SmsSendStatusReceiver : BroadcastReceiver`: on SMS_SENT with `resultCode == Activity.RESULT_OK` → update row `type = MESSAGE_TYPE_SENT`; other result codes → `type = MESSAGE_TYPE_FAILED`. On SMS_DELIVERED → `status = Telephony.Sms.STATUS_COMPLETE`. Pure helper `internal fun sentUpdateFor(action: String, resultCode: Int): ContentValues?` carries the decision so it unit-tests without a receiver.

ViewModel: `fun onSendText(body: String)` (uses the thread's single address; no-op for groups), `fun onRetry(message: Message)` — retry rewrites the failed row to OUTBOX (update type) and calls `SmsSender.send` again with the same body, deleting the failed row (`Telephony.Sms.CONTENT_URI/{id}`) to avoid duplicates. Composer UI: `OutlinedTextField` + send `IconButton` (`Icons.AutoMirrored.Filled.Send`), disabled when blank or group thread; strings `message_compose_hint` ("Text message" / "Tekstiviesti").

- [ ] **Step 1: Failing sender test** — Robolectric: `send()` inserts an OUTBOX row and `ShadowSmsManager.getLastSentMultipartTextMessageParams()` captures address/parts; delivery intent non-null on the last part only. (`FakeTelephonyProvider` handles the `insert` by appending to `smsRows` and returning `content://sms/42` — extend it.)
- [ ] **Step 2: Verify failure; implement; verify pass.**
- [ ] **Step 3: Failing receiver decision test** — `sentUpdateFor(SMS_SENT, RESULT_OK)` → SENT values; `sentUpdateFor(SMS_SENT, SmsManager.RESULT_ERROR_GENERIC_FAILURE)` → FAILED; `sentUpdateFor(SMS_DELIVERED, RESULT_OK)` → STATUS_COMPLETE values.
- [ ] **Step 4: Implement receiver + manifest entry; verify pass.**
- [ ] **Step 5: ViewModel + composer UI test-first (send calls the seam; retry path rewrites); implement.**
- [ ] **Step 6: Gates; commit** — `git commit -m "Send text messages with delivery reports"`.

---

### Task 8: SIM selection for sending

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/MessagingSims.kt`
- Modify: `ConversationViewModel`, `ConversationScreen` (SIM chip)
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/messaging/MessagingSimsTest.kt` + ViewModel extension

**Interfaces:**
- Consumes: `SubscriptionManager` (READ_PHONE_STATE already granted for calls), Task 7 sender.
- Produces:

```kotlin
data class MessagingSim(val subscriptionId: Int, val label: String)

interface MessagingSims {
    /** Active SIMs able to send messages; empty without permission. */
    fun sims(): List<MessagingSim>
    /** Android's default messaging subscription, or the only SIM's id, or -1. */
    fun defaultSubscriptionId(): Int
}
```

`AndroidMessagingSims`: `SubscriptionManager.from(context).activeSubscriptionInfoList` → label from `displayName`; default from `SubscriptionManager.getDefaultSmsSubscriptionId()`, falling back to the single active SIM when invalid (< 0). ViewModel holds `selectedSubscriptionId` (per-open-conversation override, initialized from `defaultSubscriptionId()` on every screen open — do NOT persist). Chip UI: visible only when `sims().size >= 2`, shows the selected SIM's label next to the send button, tap cycles to the next SIM (`AssistChip`). String `message_sim_chip_description` ("Sending SIM" / "Lähettävä SIM") as contentDescription.

- [ ] **Step 1: Failing tests** — fake `SubscriptionManager` is unmockable; make `AndroidMessagingSims` thin and test the pure fallback `internal fun pickDefaultSubscription(defaultId: Int, active: List<MessagingSim>): Int` (invalid default + one SIM → that SIM; invalid + many → -1; valid → itself). ViewModel test: chip cycles SIMs, send passes the selected id to `SmsSender`.
- [ ] **Step 2–5: Standard TDD cycle; implement; gates.**
- [ ] **Step 6: Commit** — `git commit -m "Choose the sending SIM per conversation"`.

---

### Task 9: Incoming SMS — receiver, blocked silencing, notification

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/SmsDeliverReceiver.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/IncomingSmsHandler.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/MessageNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: strings (en + fi)
- Test: `app/src/test/java/org/jarsi/arkphone/messaging/IncomingSmsHandlerTest.kt`, `.../MessageNotifierTest.kt`

**Interfaces:**
- Consumes: `BlockedNumbersRepository.isBlocked(number)`, `ContactsRepository.lookupContact(number)`, Task 2 repository (`refresh()` after insert so open screens update).
- Produces:

```kotlin
/** Everything that happens to one delivered SMS, testable without a receiver. */
class IncomingSmsHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blockedNumbers: BlockedNumbersRepository,
    private val messageNotifier: MessageNotifier,
    private val messagesRepository: MessagesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Returns the inserted row URI (null on failure). Blocked senders are
     *  stored already read and produce no notification. */
    suspend fun handle(address: String, body: String, timestampMillis: Long, subscriptionId: Int): Uri?
}
```

`MessageNotifier` (follow `MissedCallNotifier` structurally): channel `CHANNEL_MESSAGES = "messages"` at `IMPORTANCE_HIGH` with badge; per-thread notification id `(10_000 + threadId % 10_000).toInt()`; `NotificationCompat.MessagingStyle(Person.Builder().setName(selfName).build())` with the sender as a `Person` (name from contact lookup, else the number); `setCategory(CATEGORY_MESSAGE)`, content intent opens `ConversationActivity.intent(context, threadId)` wrapped in `TaskStackBuilder` so back returns to the app. `fun notifyMessage(threadId: Long, address: String, displayName: String?, body: String, timestampMillis: Long)` and `fun cancelThread(threadId: Long)`. Quick-reply/mark-read actions arrive in Task 10.

Manifest:

```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_MMS" />
<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH" />

<receiver
    android:name=".messaging.SmsDeliverReceiver"
    android:permission="android.permission.BROADCAST_SMS"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_DELIVER" />
    </intent-filter>
</receiver>
```

`SmsDeliverReceiver` (`@AndroidEntryPoint`, field-inject the handler + `@ApplicationScope` scope): `Telephony.Sms.Intents.getMessagesFromIntent(intent)` → all parts share one originating address; body = parts joined in order; timestamp = first part's `timestampMillis`; subscription from `intent.getIntExtra("subscription", -1)`. Use `goAsync()` + scope.launch + `pendingResult.finish()` in a `finally`.

Insert values: `address, body, date = System.currentTimeMillis(), date_sent = timestampMillis, read = if (blocked) 1 else 0, seen = if (blocked) 1 else 0, type = MESSAGE_TYPE_INBOX, sub_id, thread_id = Threads.getOrCreateThreadId(context, address)`.

- [ ] **Step 1: Failing handler tests** (Robolectric + FakeTelephonyProvider): normal sender → row inserted `read=0` + notifier called with resolved name; blocked sender (fake `BlockedNumbersRepository` returning true) → row `read=1`, notifier NOT called.
- [ ] **Step 2: Verify failure; implement handler + notifier; verify pass.** Notifier test: Robolectric `ShadowNotificationManager` asserts channel importance HIGH and a posted notification per thread id.
- [ ] **Step 3: Receiver wiring + manifest; gates.**
- [ ] **Step 4: Commit** — `git commit -m "Receive text messages and notify, silencing blocked senders"`.

---

### Task 10: Quick reply and mark-read from the notification

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/MessageActionReceiver.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/messaging/MessageNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml` (receiver, not exported)
- Modify: strings (en + fi: `message_reply` "Reply"/"Vastaa", `message_mark_read` "Mark as read"/"Merkitse luetuksi")
- Test: `app/src/test/java/org/jarsi/arkphone/messaging/MessageActionReceiverTest.kt`

**Interfaces:**
- Consumes: `SmsSender` (Task 7), `MessagesRepository.markThreadRead`, `MessagingSims.defaultSubscriptionId()` (Task 8), `MessageNotifier.cancelThread`.
- Produces: notification actions — reply via `RemoteInput` (key `org.jarsi.arkphone.extra.REPLY_TEXT`) on action `org.jarsi.arkphone.action.MESSAGE_REPLY`, and `org.jarsi.arkphone.action.MESSAGE_MARK_READ`; both intents carry `EXTRA_THREAD_ID` and `EXTRA_ADDRESS`. Receiver: reply → `SmsSender.send(address, text, defaultSubscriptionId)` then `markThreadRead` + `cancelThread`; mark-read → `markThreadRead` + `cancelThread`. `goAsync()` like Task 9.

- [ ] **Step 1: Failing receiver test** — build the intent with `RemoteInput.addResultsToIntent`, assert the fake `SmsSender` got address+text and the fake repository got `markThreadRead(threadId)`.
- [ ] **Step 2: Verify failure; implement receiver + add both actions to the notification builder in `MessageNotifier`; verify pass.**
- [ ] **Step 3: Gates; commit** — `git commit -m "Reply and mark read straight from the message notification"`.

---

### Task 11: New-message flow, SENDTO intents, in-app message buttons, headless send service

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/ui/messages/NewMessageActivity.kt` (+ `NewMessageScreen.kt`, `NewMessageViewModel.kt`)
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/HeadlessSmsSendService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/detail/CallDetailActivity.kt:77-81` (`openMessagingApp` → open our conversation)
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardActivity.kt:58` (`onMessage` likewise)
- Modify: strings (en + fi)
- Test: `app/src/test/java/org/jarsi/arkphone/ui/messages/NewMessageViewModelTest.kt`

**Interfaces:**
- Consumes: `ContactsRepository.contacts()`, `Telephony.Threads.getOrCreateThreadId`, `ConversationActivity.intent`.
- Produces: `NewMessageActivity` — a `SearchField` + contact result list (name/number filter, reuse the contacts filtering helpers) + "use typed number" row when the query looks like a number (`internal fun queryAsNumber(query: String): String?` — digits/+ only, min 3 chars); choosing a recipient resolves `getOrCreateThreadId` and forwards into `ConversationActivity` (finish self). It also handles external SENDTO:

```xml
<activity
    android:name=".ui.messages.NewMessageActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <action android:name="android.intent.action.SENDTO" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SENDTO" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="sms" />
        <data android:scheme="smsto" />
        <data android:scheme="mms" />
        <data android:scheme="mmsto" />
    </intent-filter>
</activity>
```

On SENDTO with a recipient in the URI (`intent.data?.schemeSpecificPart`), skip the picker and forward straight to the conversation. `HeadlessSmsSendService : Service` handling `TelephonyManager.ACTION_RESPOND_VIA_MESSAGE` (`android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE"`, exported, intent filter with the four schemes): read recipient from intent data + text from `Intent.EXTRA_TEXT`, send via `SmsSender` on the default SIM, `stopSelf`.

- [ ] **Step 1: Failing tests** — `queryAsNumber` pure cases ("0445..." → itself, "Matti" → null, "+35" → null); ViewModel: query filters contacts, choosing emits a `threadId` navigation event.
- [ ] **Step 2–4: TDD cycle; implement activity/screen/service; switch the two existing message buttons to `NewMessageActivity`-style direct conversation open (`ConversationActivity` after `getOrCreateThreadId(context, number)` — do the thread resolution inside a small helper `MessagingNavigator @Inject` so both activities share it).**
- [ ] **Step 5: Gates; commit** — `git commit -m "Compose new messages and answer sendto intents in-app"`.

---

### Task 12: MMS receive — WAP push, download, retrieve-conf parsing

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/data/mms/WspReader.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/data/mms/MmsPdu.kt` (NotificationInd + RetrieveConf models, parse functions)
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/WapPushReceiver.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/MmsDownloader.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/MmsFileProvider.kt` (androidx FileProvider subclass) + `app/src/main/res/xml/mms_file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `SystemMessagesRepository` (insert parsed MMS + parts; pending-download row on failure)
- Test: `app/src/test/java/org/jarsi/arkphone/data/mms/MmsPduTest.kt`, `.../messaging/MmsDownloaderTest.kt`

**Interfaces:**
- Consumes: `MessageNotifier` (Task 9), Task 3 repository.
- Produces:

```kotlin
data class NotificationInd(val transactionId: String, val contentLocation: String, val from: String?)
data class MmsPart(val mimeType: String, val body: ByteArray, val fileName: String?)
data class RetrieveConf(val from: String?, val to: List<String>, val timestampSeconds: Long, val parts: List<MmsPart>)

internal fun parseNotificationInd(pdu: ByteArray): NotificationInd?
internal fun parseRetrieveConf(pdu: ByteArray): RetrieveConf?
internal fun composeSendReq(from: String?, to: String, parts: List<MmsPart>): ByteArray  // Task 14 consumes
```

**WSP/PDU facts (verify byte-level details against `reference/fossify-messages` — package `com.klinker.android.send_message` and its vendored pdu classes — before implementing):** headers are `(field or 0x80)` bytes; well-known values: message-type `0x8C` (`m-notification-ind = 0x82`, `m-retrieve-conf = 0x84`, `m-send-req = 0x80`), transaction-id `0x98` (null-terminated text), content-location `0x83` (null-terminated text), from `0x89` (value-length-prefixed, address-present-token `0x80` + encoded string), to `0x97`, date `0x85` (long-integer), content-type `0x84` (multipart: `application/vnd.wap.multipart.related = 0xB3`, `.mixed = 0xA3`). Value encoding rule for skipping unknown headers: next byte `< 0x1F` → that byte is a length, skip it; `== 0x1F` → uintvar length follows, skip it; `>= 0x80` → single-byte value; else null-terminated text. Multipart body: uintvar part count, then per part uintvar headersLen + uintvar dataLen, part content-type at the head of the headers block, then data. `WspReader` wraps a `ByteArray` + position with `readUintvar()`, `readTextString()`, `readValueLength()`, `skipValue()`.

`MmsDownloader`: on push → `parseNotificationInd`; insert a placeholder MMS row (`Telephony.Mms.CONTENT_URI`: `thread_id` from sender, `m_type = 130`, `date` seconds, `read = 0`) so the thread shows the pending item; then `SmsManager.downloadMultimediaMessage(context, contentLocation, fileUri, null, downloadedPendingIntent)` where `fileUri` is an `MmsFileProvider` URI for a fresh file in `cacheDir/mms/`, granted with `context.grantUriPermission("com.android.phone", uri, FLAG_GRANT_WRITE_URI_PERMISSION)` (also grant `com.android.mms.service`). The downloaded `PendingIntent` fires `org.jarsi.arkphone.action.MMS_DOWNLOADED` on `MessageActionReceiver`-style receiver inside `MmsDownloader`'s companion receiver class: read the file, `parseRetrieveConf`, replace the placeholder with the real row + parts (insert parts into `content://mms/{id}/part` with `ct`, `_data` via the part stream — write bytes through `resolver.openOutputStream(partUri)`), insert sender address into `content://mms/{id}/addr` (`type = 137`), delete the temp file, `refresh()`, notify via `MessageNotifier` (body = text part or "Picture message"). On failure keep the placeholder: `pendingDownload = true` in the mapper (an mms row with `m_type = 130`), thread shows the retry row; `fun retryDownload(messageId: Long)` re-runs the download using the stored `ct_l` column (content location persists on the placeholder insert — store it).
- Blocked senders: check BEFORE notifying, exactly like Task 9 (store read, skip notification).

Manifest:

```xml
<receiver
    android:name=".messaging.WapPushReceiver"
    android:permission="android.permission.BROADCAST_WAP_PUSH"
    android:exported="true">
    <intent-filter>
        <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
        <data android:mimeType="application/vnd.wap.mms-message" />
    </intent-filter>
</receiver>

<provider
    android:name=".messaging.MmsFileProvider"
    android:authorities="org.jarsi.arkphone.mms"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/mms_file_paths" />
</provider>
```

`mms_file_paths.xml`: `<paths><cache-path name="mms" path="mms/" /></paths>`.

- [ ] **Step 1: Failing codec tests** — round-trip: `composeSendReq` output parsed by `parseRetrieveConf`-style multipart reader recovers the parts; `parseNotificationInd` on a hand-built byte fixture (message-type 0x8C 0x82, transaction 0x98 "T1\0", content-location 0x83 "http://mmsc/x\0", plus one unknown header to prove skipping) extracts all three fields.
- [ ] **Step 2: Verify failure; implement `WspReader` + parsers + composer; verify pass.** This is the plan's hardest step — budget for comparing against the reference implementation.
- [ ] **Step 3: Failing downloader test** — Robolectric: push intent byte extra → placeholder row inserted with `ct_l` stored; simulated MMS_DOWNLOADED callback with a composed retrieve-conf file → real row + text part inserted, notifier called.
- [ ] **Step 4: Implement receiver + downloader + provider; verify pass.**
- [ ] **Step 5: Gates; commit** — `git commit -m "Receive multimedia messages"`.

---

### Task 13: MMS display in conversations

**Files:**
- Modify: `app/src/main/java/org/jarsi/arkphone/data/SystemMessagesRepository.kt` (merge MMS rows into `messages()`, extend `markThreadRead` to the mms table)
- Modify: `ConversationScreen` (image bubbles, pending-download retry row, full-screen viewer dialog)
- Modify: strings (en + fi: `mms_download_failed_retry` "Download failed — tap to retry" / "Lataus epäonnistui — yritä uudelleen napauttamalla", `mms_picture_message` "Picture message" / "Kuvaviesti")
- Test: extend `SystemMessagesRepositoryTest` + `ConversationScreenTest`

**Interfaces:**
- Consumes: Task 12 rows/parts, Task 1 `MmsAttachment`.
- Produces: `messages(threadId)` returns SMS+MMS merged sorted by timestamp; MMS mapping: query `Telephony.Mms.CONTENT_URI` (`_id, thread_id, date, msg_box, read, sub_id, m_type, ct_l`), per row query `content://mms/{id}/part` (`_id, ct, text`) — `text/plain` part → body, image/* parts → `MmsAttachment("content://mms/part/{partId}", ct)`; sender address from `content://mms/{id}/addr` where `type = 137`; `incoming = msg_box == Telephony.Mms.MESSAGE_BOX_INBOX`; `pendingDownload = m_type == 130`. UI: `AsyncImage(model = attachment.partUri)` in the bubble (Coil loads content URIs), tap → full-screen `Dialog` with the image; pending-download row is a tappable bubble calling `viewModel.onRetryDownload(message.id)` → `MmsDownloader.retryDownload`.

- [ ] **Step 1: Failing repository test** — seed one SMS + one MMS row (with text and image part) in the fake provider; assert merged order by time, MMS body from the text part, attachment URI shape, pendingDownload for `m_type = 130`.
- [ ] **Step 2: Verify failure; implement; verify pass.**
- [ ] **Step 3: Compose test** — image bubble renders (`testTag("mms_image")`); retry row shows the string and fires the callback.
- [ ] **Step 4: Implement UI; gates.**
- [ ] **Step 5: Commit** — `git commit -m "Show multimedia messages in conversations"`.

---

### Task 14: MMS send — pick, compress, send

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/MmsSender.kt` (+ `AndroidMmsSender`)
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/ImageShrinker.kt`
- Modify: `ConversationViewModel`/`ConversationScreen` (attach button + preview row)
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/messaging/ImageShrinkerTest.kt`, `.../AndroidMmsSenderTest.kt`, ViewModel extension

**Interfaces:**
- Consumes: `composeSendReq` (Task 12), `MmsFileProvider`, `MessagingSims`.
- Produces:

```kotlin
fun interface MmsSender {
    /** Sends one image (+ optional text) to one recipient. Returns the
     *  provider row URI, or null when the send could not start. */
    suspend fun send(address: String, text: String?, imageUri: Uri, subscriptionId: Int): Uri?
}

/** Re-encodes [source] as JPEG under [maxBytes]; scales down in steps. */
class ImageShrinker @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun shrink(source: Uri, maxBytes: Int): ByteArray?
}
```

`AndroidMmsSender`: max size from `CarrierConfigManager.getConfigForSubId(subId).getInt(CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT)` (fallback 300 * 1024 when 0/absent), budget `maxBytes * 9 / 10` for the image; `composeSendReq` with an image part (+ text part when text non-blank); write PDU bytes to a `cacheDir/mms/` file, insert the outbox MMS row + parts into the provider (`msg_box = MESSAGE_BOX_OUTBOX`), then `SmsManager.createForSubscriptionId(subId).sendMultimediaMessage(context, fileUri, null, null, sentPendingIntent)`; sent callback (action `org.jarsi.arkphone.action.MMS_SENT` on `SmsSendStatusReceiver`) updates `msg_box` to SENT (RESULT_OK) or marks failure (`msg_box = MESSAGE_BOX_OUTBOX` + a `resp_st` error column write is unreliable — reuse the sms pattern: move to `MESSAGE_BOX_FAILED = 5`). `ImageShrinker`: decode bounds → `inSampleSize` power of two to ≤ 2048px, then quality loop 90→40 in steps of 10 until under budget, else scale halved and repeat; null when undecodable. UI: attach `IconButton` (`Icons.Filled.Image`) → `ActivityResultContracts.PickVisualMedia` (photo picker needs no permission), preview thumbnail above the composer with a remove ×; send with an attachment routes to `MmsSender` (works also when text present; group threads still excluded).

- [ ] **Step 1: Failing shrinker test** — Robolectric: a generated 4000×3000 bitmap written to a temp file shrinks under 100 kB and stays decodable; corrupt input → null.
- [ ] **Step 2: Verify failure; implement; verify pass.**
- [ ] **Step 3: Failing sender test** — fake provider captures outbox row + parts; `ShadowSmsManager` (or a `SmsManager`-wrapping seam if the shadow lacks `sendMultimediaMessage`) captures the send with our file URI.
- [ ] **Step 4: Implement; ViewModel/UI test-first for the attach flow; verify pass.**
- [ ] **Step 5: Gates; commit** — `git commit -m "Send picture messages"`.

---

### Task 15: Default-SMS role, tab badge, final sweep

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/messaging/SmsRole.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/messages/MessagesScreen.kt` + `MessagesViewModel.kt` (role banner)
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/navigation/MainScreen.kt` (unread badge)
- Modify: `app/src/main/java/org/jarsi/arkphone/MainActivity.kt` (role result launcher → refresh)
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Modify: strings (en + fi)
- Test: `app/src/test/java/org/jarsi/arkphone/messaging/SmsRoleTest.kt`, ViewModel/Screen extensions

**Interfaces:**
- Consumes: everything above; `CallScreeningRole` as the structural model.
- Produces:

```kotlin
/** The default-SMS-app role. Only its holder receives messages and may
 *  write the telephony provider. */
interface SmsRole {
    fun isHeld(): Boolean
    fun requestIntent(): Intent?
}
```

`AndroidSmsRole`: `RoleManager.ROLE_SMS` via `isRoleHeld`/`createRequestRoleIntent` (ROLE_SMS exists from API 29; below Q use `Telephony.Sms.getDefaultSmsPackage(context) == context.packageName` for `isHeld` and `Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)` for the request). Banner on the Messages tab when `!isHeld()` (string `messages_banner_not_default` = "ARK-phone is not your SMS app" / "ARK-phone ei ole viestisovelluksesi", button `messages_banner_set_default` = "Set as default" / "Aseta oletukseksi") — reuse the `DefaultDialerBanner` composable pattern. Composer + reply actions disabled without the role (ViewModel exposes `canSend: Boolean`). Tab badge: `MessagesViewModel.unreadCount: StateFlow<Int>` (count of unread conversations) hoisted — `MainScreen` takes `unreadMessages: Int` as a parameter from `MainActivity` (collect there with the existing lifecycle patterns) and wraps the tab icon in `BadgedBox` when > 0.

- [ ] **Step 1: Failing tests** — `AndroidSmsRole` Robolectric with `ShadowRoleManager.addHeldRole(RoleManager.ROLE_SMS)`; ViewModel `canSend` false without role; Compose test: banner visible without role, badge shows the unread count.
- [ ] **Step 2: Verify failure; implement; verify pass.**
- [ ] **Step 3: Full manifest review against the spec's four required components** (SMS_DELIVER receiver, WAP_PUSH_DELIVER receiver, SENDTO activity, HeadlessSmsSendService) — all landed in Tasks 9/11/12; fix any drift now.
- [ ] **Step 4: Finnish strings sweep** — every `R.string.message*`/`mms*`/`tab_messages` key present in `values-fi`; run `.\gradlew.bat :app:lintDebug` (MissingTranslation fails the build).
- [ ] **Step 5: Full gates; commit** — `git commit -m "Take the default messaging role and finish the messages tab"`.

---

## Field verification after Task 15 (manual, on the Pixel 8a debug build first)

1. Install, open Messages tab → existing SMS conversations appear (read-only, banner visible).
2. Take the role via the banner → send a text to the 10 Pro; watch sending → sent → delivered states.
3. Reply from the 10 Pro → notification with quick reply; reply from the notification.
4. Send an MMS picture both directions (DNA APN must have MMS configured).
5. Block a test number in the app → its SMS arrives silently, visible in the thread, no notification.
6. Dual-SIM: chip visible on the 8a, hidden on single-SIM devices; default follows Android's messaging SIM setting.
7. Switch default SMS app back to Google Messages and back again — nothing lost either way.
