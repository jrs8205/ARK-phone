# VoIP Phase 1 — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give ARK Phone a server-issued ARK identity, device-only contact links, FCM wake-up, self-managed Telecom calls in the existing in-call UI, and an automatic VoIP-or-carrier routing branch — all debug-scoped, so release builds gain no permissions, dependencies or visible features.

**Architecture:** The signaling/WebRTC/Firebase engine stays in `app/src/debug/java/org/jarsi/arkphone/voip/` (its libwebrtc and Firebase dependencies are `debugImplementation`), while identity storage, the link table, the settings and contact-card UI and the routing branch live in the main tree as final code. The two source sets meet at two small interfaces — `VoipAccountGateway` and `VoipCallGateway` — declared in the main tree with Dagger `@BindsOptionalOf`, so a release build resolves `Optional.empty()` and every VoIP surface is inert and invisible. Incoming calls arrive as a data-only FCM push that wakes the process, opens the inbox WebSocket, drains the buffered flush, and rings through the same `CallController` → `CallNotifications` → `InCallActivity` path carrier calls already use.

**Tech Stack:** Kotlin 2.3.21, Hilt 2.59.2, Jetpack Compose (BOM 2026.06.01), Room 2.8.2, DataStore Preferences 1.1.7, `androidx.core:core-telecom:1.0.1`, `com.google.firebase:firebase-messaging:25.1.1` + `com.google.gms.google-services:4.5.0`, OkHttp 5.4.0, kotlinx-serialization-json 1.11.0, stream-webrtc-android 1.3.10, JUnit4 + Robolectric 4.16 + Turbine 1.2.1 with hand-written fakes.

## Global Constraints

- **Test gate, run from `C:\Users\jrs82\Downloads\ARK-phone` after every task:** `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`
- **Lint is `warningsAsErrors = true`** (`app/build.gradle.kts`): one new warning fails the gate. In particular every string added to `app/src/main/res/values/strings.xml` MUST get a matching entry in `app/src/main/res/values-fi/strings.xml`, or lint `MissingTranslation` fails.
- **English only** in code, comments, strings and commit messages. **No AI mentions** anywhere (no co-author trailers, no tool names).
- **Commit in the PARENT repo on branch `feature/voip-spike`** (`C:\Users\jrs82\Downloads\ARK-phone`). `worker/` is a separate nested git repository that this plan never modifies — it is read-only reference.
- **Release must stay untouched:** every new dependency is `debugImplementation`; every new manifest entry goes in `app/src/debug/AndroidManifest.xml`; no new permission reaches `app/src/main/AndroidManifest.xml`. Main-tree VoIP code compiles into release but is inert because the `Optional<VoipAccountGateway>` / `Optional<VoipCallGateway>` bindings are empty there.
- **Secrets:** `arkphone.voip.workerUrl` lives in `local.properties` (gitignored) and reaches code only as `BuildConfig.VOIP_WORKER_URL` in the debug build type. `app/google-services.json` is NOT committed; the `com.google.gms.google-services` plugin is applied only when that file exists, exactly as the signing config exists only when the `ARKPHONE_STORE_FILE` property is present. **The build must succeed with the file absent.**
- **`worker/docs/protocol.md` is the authority for every message shape, cap, status code and client rule.** If code and that document disagree, re-read the document. Never modify anything under `worker/`.
- **Never commit `Turn Token.txt`** or any file containing a device token, worker token or Firebase key.
- Follow existing repo patterns: DataStore repository shape (`app/src/main/java/org/jarsi/arkphone/data/DataStoreSpeedDialRepository.kt`), hand-written fakes only in `app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt` (no MockK/Mockito), `MainDispatcherRule`, version catalog `gradle/libs.versions.toml`, Robolectric tests as `@RunWith(AndroidJUnit4::class) @Config(sdk = [35])`, backtick or camelCase test names as the neighbouring file uses.
- **Windows DataStore trap:** a test may perform only ONE DataStore write per store file; a second rename-over write to the same open file fails with `IOException`. Split writes across tests.
- **Coroutine test trap from Phase 0:** never leave a `while (true)` loop running on a virtual clock inside `runTest`; cancel the owning scope inside the `runTest` body (`try`/`finally`), `@After` is too late.

## Verified API facts (do not re-guess)

Checked 2026-08-08 against the real `androidx.core:core-telecom:1.0.1` sources jar from `dl.google.com` and `maven-metadata.xml` (latest stable is **1.0.1**; 1.1.0-alpha06 is the tip and is NOT used):

```kotlin
@RequiresApi(android.os.Build.VERSION_CODES.O)
public class CallsManager(context: Context)

// companion constants
CallsManager.CAPABILITY_BASELINE                 // 1 shl 0
CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING   // 1 shl 1
CallsManager.CAPABILITY_SUPPORTS_CALL_STREAMING  // 1 shl 2

@RequiresPermission("android.permission.MANAGE_OWN_CALLS")
public fun registerAppWithTelecom(capabilities: Int)   // 1.0.1 takes ONE argument

@RequiresPermission("android.permission.MANAGE_OWN_CALLS")
public suspend fun addCall(
    callAttributes: CallAttributesCompat,
    onAnswer: suspend (callType: Int) -> Unit,
    onDisconnect: suspend (disconnectCause: android.telecom.DisconnectCause) -> Unit,
    onSetActive: suspend () -> Unit,
    onSetInactive: suspend () -> Unit,
    block: CallControlScope.() -> Unit,
): Unit                       // does NOT return until the call session ends

public class CallAttributesCompat(
    public val displayName: CharSequence,
    public val address: android.net.Uri,
    public val direction: Int,
    public val callType: Int = CALL_TYPE_AUDIO_CALL,
    public val callCapabilities: Int = SUPPORTS_SET_INACTIVE,   // Int, NOT a CallCapability object
    public val preferredStartingCallEndpoint: CallEndpointCompat? = null,
)
CallAttributesCompat.DIRECTION_INCOMING = 1
CallAttributesCompat.DIRECTION_OUTGOING = 2
CallAttributesCompat.CALL_TYPE_AUDIO_CALL = 1
CallAttributesCompat.SUPPORTS_SET_INACTIVE = 1 shl 1

public interface CallControlScope : CoroutineScope {
    public fun getCallId(): android.os.ParcelUuid
    public suspend fun setActive(): CallControlResult
    public suspend fun setInactive(): CallControlResult
    public suspend fun answer(callType: Int): CallControlResult
    public suspend fun disconnect(disconnectCause: android.telecom.DisconnectCause): CallControlResult
    public suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult
    public val currentCallEndpoint: Flow<CallEndpointCompat>
    public val availableEndpoints: Flow<List<CallEndpointCompat>>
    public val isMuted: Flow<Boolean>
}

public sealed class CallControlResult {
    public class Success : CallControlResult()                 // a class, not an object
    public class Error(public val errorCode: Int) : CallControlResult()
}
```

The library's own `AndroidManifest.xml` already declares `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`, the `JetpackConnectionService` and a `MuteStateReceiver`; because the artifact is added with `debugImplementation`, all of that merges into the **debug** manifest only. This plan still declares `MANAGE_OWN_CALLS` explicitly in `app/src/debug/AndroidManifest.xml` so the permission is visible where the other VoIP permissions are.

## Names this plan defines (kept identical across tasks)

Main tree, package `org.jarsi.arkphone.voip` unless stated:

- `object ArkCode { val PATTERN: Regex; fun isValid(code: String): Boolean; fun canonicalize(input: String): String? }`
- `fun arkLinkKey(number: String): String`
- `data class ArkLink(numberKey, number, code, nickname, publicKey, linkedAtMillis)`
- `interface ArkLinkRepository { val links: Flow<List<ArkLink>>; suspend fun link(number, code, nickname, publicKey, atMillis); suspend fun unlink(number) }`
- `class ArkLinkCache { val current: Map<String, ArkLink>; fun linkFor(number: String): ArkLink?; suspend fun await(): Map<String, ArkLink> }`
- `data class ArkIdentity(code, nickname, deviceToken)` and `interface ArkIdentityRepository` (package `org.jarsi.arkphone.data`)
- `data class ArkRegistration(code, deviceToken)`, `data class ArkAccount(code, nickname, publicKey)`
- `interface VoipAccountGateway { suspend fun register(nickname: String): ArkRegistration?; suspend fun lookUp(code: String): ArkAccount? }`
- `interface VoipCallGateway { fun startCall(link: ArkLink, onFallbackToCarrier: () -> Unit): Boolean }`
- `class CallRouter` (package `org.jarsi.arkphone.telecom`)

Debug tree, package `org.jarsi.arkphone.voip`:

- `SignalingClient`, `WebSocketConnector`, `WebSocketHandle`, `SignalingMessage`, `SignalingTypes`, `SignalingJson`
- `ArkHttp`, `ArkHttpResponse`, `OkHttpArkHttp`, `ArkAccountClient`
- `ArkKeyPairSource`, `spkiBase64`
- `fcm.ArkMessagingService`, `fcm.FcmTokenSync`
- `VoipEngine`, `VoipCallSession` (renamed from `WebRtcCallSession` only in signature, file kept)
- `telecom.VoipCallHandle`, `telecom.VoipTelecom`, `telecom.CoreTelecomRegistrar`

---

## Stage B1: Identity — registration, settings, contact linking

### Task 1: ARK code validation and link key

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/voip/ArkCode.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/voip/ArkCodeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `org.jarsi.arkphone.voip.ArkCode.isValid(String): Boolean`, `ArkCode.canonicalize(String): String?`, `ArkCode.PATTERN: Regex`, `org.jarsi.arkphone.voip.arkLinkKey(String): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/voip/ArkCodeTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArkCodeTest {

    @Test
    fun `a well formed code is valid`() {
        assertTrue(ArkCode.isValid("ARK-7K3M-Q2FP"))
    }

    @Test
    fun `the confusable characters are not in the alphabet`() {
        assertFalse(ArkCode.isValid("ARK-0O1I-LLLL"))
    }

    @Test
    fun `lowercase, short, long and padded codes are invalid`() {
        assertFalse(ArkCode.isValid("ark-7k3m-q2fp"))
        assertFalse(ArkCode.isValid("ARK-7K3M-Q2F"))
        assertFalse(ArkCode.isValid("ARK-7K3M-Q2FPX"))
        assertFalse(ArkCode.isValid(" ARK-7K3M-Q2FP"))
        assertFalse(ArkCode.isValid("ARK-7K3M-Q2FP\n"))
    }

    @Test
    fun `canonicalize repairs pasted text`() {
        assertEquals("ARK-7K3M-Q2FP", ArkCode.canonicalize("  ark-7k3m-q2fp \n"))
        assertEquals("ARK-7K3M-Q2FP", ArkCode.canonicalize("ARK 7K3M Q2FP"))
        assertEquals("ARK-7K3M-Q2FP", ArkCode.canonicalize("7k3mq2fp"))
    }

    @Test
    fun `canonicalize refuses anything that is not a code`() {
        assertNull(ArkCode.canonicalize("hello"))
        assertNull(ArkCode.canonicalize("ARK-0O1I-LLLL"))
        assertNull(ArkCode.canonicalize(""))
    }

    @Test
    fun `the link key is the last nine digits of a number`() {
        assertEquals("445552841", arkLinkKey("+358 44 5552841"))
        assertEquals("445552841", arkLinkKey("044 555 2841"))
        assertEquals("91234567", arkLinkKey("09 1234567"))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.ArkCodeTest"
```

Expected: compilation failure — `Unresolved reference: ArkCode`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/org/jarsi/arkphone/voip/ArkCode.kt`:

```kotlin
package org.jarsi.arkphone.voip

/**
 * ARK codes as the signaling worker defines them (worker/docs/protocol.md §1):
 * `ARK-XXXX-XXXX` over a 31-character alphabet with no 0, O, 1, I or L.
 * The worker anchors its pattern, so leading or trailing whitespace is a
 * rejection, not something the server trims — canonicalize before sending.
 */
object ArkCode {

    const val ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    val PATTERN: Regex = Regex("^ARK-[$ALPHABET]{4}-[$ALPHABET]{4}$")

    fun isValid(code: String): Boolean = PATTERN.matches(code)

    /**
     * Accepts what a person can realistically paste — mixed case, spaces,
     * missing dashes, a trailing newline from an SMS — and returns the exact
     * 13-character form the worker accepts, or null when the input is not a
     * code at all.
     */
    fun canonicalize(input: String): String? {
        val body = input.uppercase()
            .filter { it in ALPHABET }
            .removePrefix("ARK")
        if (body.length != 8) return null
        val code = "ARK-${body.take(4)}-${body.drop(4)}"
        return code.takeIf(::isValid)
    }
}

/**
 * Key a number↔code link is stored and looked up under. The last nine digits
 * are what `sameCaller` already treats as one number, so national and
 * international spellings of the same phone land on one row.
 */
fun arkLinkKey(number: String): String = number.filter(Char::isDigit).takeLast(9)
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL, `ArkCodeTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/voip/ArkCode.kt app/src/test/java/org/jarsi/arkphone/voip/ArkCodeTest.kt
git commit -m "Add ARK code validation and link key"
```

---

### Task 2: Device-only number-to-code link table

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/voip/ArkLink.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/data/RoomArkLinkRepository.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/data/ArkPhoneDatabase.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/RoomArkLinkRepositoryTest.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/ArkLinkMigrationTest.kt`

**Interfaces:**
- Consumes: `arkLinkKey(String)` from Task 1.
- Produces: `org.jarsi.arkphone.voip.ArkLink`, `org.jarsi.arkphone.voip.ArkLinkRepository`, `org.jarsi.arkphone.data.ArkLinkEntity`, `ArkLinkDao`, `ArkPhoneDatabase.MIGRATION_2_3`, `RoomArkLinkRepository`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/data/RoomArkLinkRepositoryTest.kt`:

```kotlin
package org.jarsi.arkphone.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomArkLinkRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomArkLinkRepository(db.arkLinkDao())

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aLinkComesBackWithItsNicknameAndKey() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-test", 1_000L)
        val link = repository.links.first().single()
        assertEquals("445552841", link.numberKey)
        assertEquals("+358 44 5552841", link.number)
        assertEquals("ARK-7K3M-Q2FP", link.code)
        assertEquals("Jarsi", link.nickname)
        assertEquals("pk-test", link.publicKey)
        assertEquals(1_000L, link.linkedAtMillis)
    }

    @Test
    fun relinkingTheSameNumberReplacesTheRowInsteadOfAddingOne() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-1", 1_000L)
        repository.link("044 555 2841", "ARK-AAAA-BBBB", "Jarsi 2", "pk-2", 2_000L)
        val links = repository.links.first()
        assertEquals(1, links.size)
        assertEquals("ARK-AAAA-BBBB", links.single().code)
    }

    @Test
    fun unlinkingMatchesAnyFormattingOfTheNumber() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-1", 1_000L)
        repository.unlink("044 555 2841")
        assertEquals(emptyList<Any>(), repository.links.first())
    }

    @Test
    fun unlinkingAnUnknownNumberLeavesTheTableAlone() = runTest {
        repository.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk-1", 1_000L)
        repository.unlink("+358 40 1112223")
        assertEquals(1, repository.links.first().size)
        assertNull(repository.links.first().firstOrNull { it.code == "ARK-AAAA-BBBB" })
    }
}
```

Create `app/src/test/java/org/jarsi/arkphone/data/ArkLinkMigrationTest.kt`:

```kotlin
package org.jarsi.arkphone.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val DB_NAME = "ark-link-migration-test.db"

private const val V2_TABLE =
    "CREATE TABLE IF NOT EXISTS `whatsapp_calls` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`callerName` TEXT, `callerNumber` TEXT, `type` TEXT NOT NULL, " +
        "`timestampMillis` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, " +
        "`isVideo` INTEGER NOT NULL, " +
        "`sourcePackage` TEXT NOT NULL DEFAULT 'com.whatsapp')"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkLinkMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dbFile = context.getDatabasePath(DB_NAME)

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    private fun createVersion2Database() {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(V2_TABLE)
            db.execSQL(
                "INSERT INTO whatsapp_calls " +
                    "(callerName, callerNumber, type, timestampMillis, durationSeconds, " +
                    "isVideo, sourcePackage) " +
                    "VALUES ('Matti', '+358 44 5552841', 'INCOMING', 1000, 30, 0, 'com.whatsapp')",
            )
            db.version = 2
        }
    }

    @Test
    fun migrationKeepsWhatsAppRowsAndAddsAnEmptyLinkTable() = runTest {
        createVersion2Database()
        val db = Room.databaseBuilder(context, ArkPhoneDatabase::class.java, DB_NAME)
            .addMigrations(ArkPhoneDatabase.MIGRATION_1_2, ArkPhoneDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(1, db.whatsAppCallDao().calls().first().size)
            assertTrue(db.arkLinkDao().links().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun theMigratedDatabaseAcceptsLinkRows() = runTest {
        createVersion2Database()
        val db = Room.databaseBuilder(context, ArkPhoneDatabase::class.java, DB_NAME)
            .addMigrations(ArkPhoneDatabase.MIGRATION_1_2, ArkPhoneDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            db.arkLinkDao().upsert(
                ArkLinkEntity(
                    numberKey = "445552841",
                    number = "+358 44 5552841",
                    code = "ARK-7K3M-Q2FP",
                    nickname = "Jarsi",
                    publicKey = "pk-test",
                    linkedAtMillis = 1_000L,
                ),
            )
            assertEquals("ARK-7K3M-Q2FP", db.arkLinkDao().links().first().single().code)
        } finally {
            db.close()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.RoomArkLinkRepositoryTest" --tests "org.jarsi.arkphone.data.ArkLinkMigrationTest"
```

Expected: compilation failure — `Unresolved reference: RoomArkLinkRepository`, `arkLinkDao`, `ArkLinkEntity`, `MIGRATION_2_3`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/org/jarsi/arkphone/voip/ArkLink.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.Flow

/**
 * A number↔ARK-code link. Links live only on this device: nothing about the
 * contact list is ever sent to the worker.
 */
data class ArkLink(
    val numberKey: String,
    val number: String,
    val code: String,
    val nickname: String,
    val publicKey: String,
    val linkedAtMillis: Long,
)

interface ArkLinkRepository {
    val links: Flow<List<ArkLink>>

    suspend fun link(
        number: String,
        code: String,
        nickname: String,
        publicKey: String,
        atMillis: Long,
    )

    suspend fun unlink(number: String)
}
```

Replace the whole content of `app/src/main/java/org/jarsi/arkphone/data/ArkPhoneDatabase.kt` with:

```kotlin
package org.jarsi.arkphone.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "whatsapp_calls")
data class WhatsAppCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callerName: String?,
    val callerNumber: String?,
    val type: String,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val isVideo: Boolean,
    @ColumnInfo(defaultValue = "com.whatsapp") val sourcePackage: String = "com.whatsapp",
)

@Dao
interface WhatsAppCallDao {
    @Query("SELECT * FROM whatsapp_calls ORDER BY timestampMillis DESC")
    fun calls(): Flow<List<WhatsAppCallEntity>>

    @Query("SELECT * FROM whatsapp_calls")
    suspend fun callsOnce(): List<WhatsAppCallEntity>

    @Insert
    suspend fun insert(call: WhatsAppCallEntity)

    @Query("DELETE FROM whatsapp_calls WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

/** One device-only link between a phone number and an ARK account. */
@Entity(tableName = "ark_links")
data class ArkLinkEntity(
    @PrimaryKey val numberKey: String,
    val number: String,
    val code: String,
    val nickname: String,
    val publicKey: String,
    val linkedAtMillis: Long,
)

@Dao
interface ArkLinkDao {
    @Query("SELECT * FROM ark_links ORDER BY nickname")
    fun links(): Flow<List<ArkLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ArkLinkEntity)

    @Query("DELETE FROM ark_links WHERE numberKey = :numberKey")
    suspend fun delete(numberKey: String)
}

@Database(
    entities = [WhatsAppCallEntity::class, ArkLinkEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ArkPhoneDatabase : RoomDatabase() {
    abstract fun whatsAppCallDao(): WhatsAppCallDao

    abstract fun arkLinkDao(): ArkLinkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE whatsapp_calls " +
                        "ADD COLUMN sourcePackage TEXT NOT NULL DEFAULT 'com.whatsapp'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ark_links` (" +
                        "`numberKey` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, " +
                        "`code` TEXT NOT NULL, " +
                        "`nickname` TEXT NOT NULL, " +
                        "`publicKey` TEXT NOT NULL, " +
                        "`linkedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`numberKey`))",
                )
            }
        }
    }
}
```

Create `app/src/main/java/org/jarsi/arkphone/data/RoomArkLinkRepository.kt`:

```kotlin
package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.ArkLinkRepository
import org.jarsi.arkphone.voip.arkLinkKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomArkLinkRepository @Inject constructor(
    private val dao: ArkLinkDao,
) : ArkLinkRepository {

    override val links: Flow<List<ArkLink>> = dao.links().map { rows ->
        rows.map { row ->
            ArkLink(
                numberKey = row.numberKey,
                number = row.number,
                code = row.code,
                nickname = row.nickname,
                publicKey = row.publicKey,
                linkedAtMillis = row.linkedAtMillis,
            )
        }
    }

    override suspend fun link(
        number: String,
        code: String,
        nickname: String,
        publicKey: String,
        atMillis: Long,
    ) {
        dao.upsert(
            ArkLinkEntity(
                numberKey = arkLinkKey(number),
                number = number,
                code = code,
                nickname = nickname,
                publicKey = publicKey,
                linkedAtMillis = atMillis,
            ),
        )
    }

    override suspend fun unlink(number: String) {
        dao.delete(arkLinkKey(number))
    }
}
```

In `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`, add these imports next to the other `org.jarsi.arkphone.data` imports:

```kotlin
import org.jarsi.arkphone.data.ArkLinkDao
import org.jarsi.arkphone.data.RoomArkLinkRepository
import org.jarsi.arkphone.voip.ArkLinkRepository
```

Add this `@Binds` next to `bindWhatsAppCallLogRepository`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindArkLinkRepository(impl: RoomArkLinkRepository): ArkLinkRepository
```

Replace `provideDatabase` and add the DAO provider in the `companion object`:

```kotlin
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): ArkPhoneDatabase =
            Room.databaseBuilder(context, ArkPhoneDatabase::class.java, "arkphone.db")
                .addMigrations(ArkPhoneDatabase.MIGRATION_1_2, ArkPhoneDatabase.MIGRATION_2_3)
                .build()

        @Provides
        fun provideArkLinkDao(db: ArkPhoneDatabase): ArkLinkDao = db.arkLinkDao()
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; both new test classes green and `WhatsAppCallMigrationTest` still green.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/voip/ArkLink.kt app/src/main/java/org/jarsi/arkphone/data/RoomArkLinkRepository.kt app/src/main/java/org/jarsi/arkphone/data/ArkPhoneDatabase.kt app/src/main/java/org/jarsi/arkphone/di/AppModule.kt app/src/test/java/org/jarsi/arkphone/data/RoomArkLinkRepositoryTest.kt app/src/test/java/org/jarsi/arkphone/data/ArkLinkMigrationTest.kt
git commit -m "Add device-only ARK link table with migration 2 to 3"
```

---

### Task 3: "ARK internet calls" master switch setting

**Files:**
- Modify: `app/src/main/java/org/jarsi/arkphone/data/model/Settings.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/data/SettingsRepository.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/data/DataStoreSettingsRepository.kt`
- Modify: `app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/DataStoreSettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Settings.arkInternetCallsEnabled: Boolean` (default `true`), `SettingsRepository.setArkInternetCallsEnabled(enabled: Boolean)`.

- [ ] **Step 1: Write the failing test**

Append to the body of the existing class in `app/src/test/java/org/jarsi/arkphone/data/DataStoreSettingsRepositoryTest.kt` (keep every existing test; the file already builds a DataStore per test — reuse that helper exactly as the neighbouring tests do, and perform at most ONE write per test because of the Windows rename-over limit):

```kotlin
    @Test
    fun arkInternetCallsAreOnUntilTheUserTurnsThemOff() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        assertTrue(repository.settings.first().arkInternetCallsEnabled)
    }

    @Test
    fun turningArkInternetCallsOffPersists() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.setArkInternetCallsEnabled(false)
        assertFalse(repository.settings.first().arkInternetCallsEnabled)
    }
```

If the file does not already import them, add `import org.junit.Assert.assertTrue` and `import org.junit.Assert.assertFalse` and `import kotlinx.coroutines.flow.first`. If the file's DataStore helper is named differently, use its existing name verbatim rather than renaming it.

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.DataStoreSettingsRepositoryTest"
```

Expected: compilation failure — `Unresolved reference: arkInternetCallsEnabled` / `setArkInternetCallsEnabled`.

- [ ] **Step 3: Implement**

In `app/src/main/java/org/jarsi/arkphone/data/model/Settings.kt`, add this property to `Settings` immediately after `blockedCallAction`:

```kotlin
    /** Master switch for ARK internet calls; links survive it being off. */
    val arkInternetCallsEnabled: Boolean = true,
```

In `app/src/main/java/org/jarsi/arkphone/data/SettingsRepository.kt`, add before the closing brace:

```kotlin
    /** Turns internet call routing off globally without removing any link. */
    suspend fun setArkInternetCallsEnabled(enabled: Boolean)
```

In `app/src/main/java/org/jarsi/arkphone/data/DataStoreSettingsRepository.kt`:

- add to `private object Keys`:

```kotlin
        val ARK_INTERNET_CALLS = booleanPreferencesKey("ark_internet_calls_enabled")
```

- add to the `Settings(...)` construction inside `map`, after `blockedCallAction = ...`:

```kotlin
                arkInternetCallsEnabled = preferences[Keys.ARK_INTERNET_CALLS] ?: true,
```

- add this override next to `setBlockedCallAction`:

```kotlin
    override suspend fun setArkInternetCallsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ARK_INTERNET_CALLS] = enabled }
    }
```

In `app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt`, add to `FakeSettingsRepository`:

```kotlin
    override suspend fun setArkInternetCallsEnabled(enabled: Boolean) {
        state.value = state.value.copy(arkInternetCallsEnabled = enabled)
    }
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/data/model/Settings.kt app/src/main/java/org/jarsi/arkphone/data/SettingsRepository.kt app/src/main/java/org/jarsi/arkphone/data/DataStoreSettingsRepository.kt app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt app/src/test/java/org/jarsi/arkphone/data/DataStoreSettingsRepositoryTest.kt
git commit -m "Add ARK internet calls master switch to settings"
```

---

### Task 4: ARK identity storage

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/data/ArkIdentityRepository.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/data/DataStoreArkIdentityRepositoryTest.kt`

**Interfaces:**
- Consumes: `provideSettingsDataStore` (`DataStore<Preferences>`, file `settings`) from `AppModule`.
- Produces: `org.jarsi.arkphone.data.ArkIdentity(code, nickname, deviceToken)`, `ArkIdentityRepository { val identity: Flow<ArkIdentity?>; suspend fun save(identity: ArkIdentity); val syncedFcmToken: Flow<String?>; suspend fun setSyncedFcmToken(token: String) }`, `DataStoreArkIdentityRepository`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/data/DataStoreArkIdentityRepositoryTest.kt`:

```kotlin
package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreArkIdentityRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }

    // One DataStore write per test: on Windows a second rename-over write to
    // the same open store file fails with an IOException.

    @Test
    fun anUnregisteredDeviceHasNoIdentity() = runTest {
        val repository = DataStoreArkIdentityRepository(createDataStore())
        assertNull(repository.identity.first())
        assertNull(repository.syncedFcmToken.first())
    }

    @Test
    fun theRegisteredIdentityRoundTrips() = runTest {
        val repository = DataStoreArkIdentityRepository(createDataStore())
        repository.save(ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"))
        assertEquals(
            ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"),
            repository.identity.first(),
        )
    }

    @Test
    fun theSyncedFcmTokenRoundTrips() = runTest {
        val repository = DataStoreArkIdentityRepository(createDataStore())
        repository.setSyncedFcmToken("fcm-1")
        assertEquals("fcm-1", repository.syncedFcmToken.first())
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.data.DataStoreArkIdentityRepositoryTest"
```

Expected: compilation failure — `Unresolved reference: DataStoreArkIdentityRepository`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/org/jarsi/arkphone/data/ArkIdentityRepository.kt`:

```kotlin
package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's ARK account. The device token is shown by the worker exactly
 * once at registration and can never be recovered, re-issued or rotated
 * (worker/docs/protocol.md §2) — it is persisted before any UI is shown.
 */
data class ArkIdentity(
    val code: String,
    val nickname: String,
    val deviceToken: String,
)

interface ArkIdentityRepository {
    /** Null until this device has registered. */
    val identity: Flow<ArkIdentity?>

    suspend fun save(identity: ArkIdentity)

    /** The FCM registration token the worker has already been told about. */
    val syncedFcmToken: Flow<String?>

    suspend fun setSyncedFcmToken(token: String)
}

@Singleton
class DataStoreArkIdentityRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ArkIdentityRepository {

    private object Keys {
        val CODE = stringPreferencesKey("ark_code")
        val NICKNAME = stringPreferencesKey("ark_nickname")
        val DEVICE_TOKEN = stringPreferencesKey("ark_device_token")
        val SYNCED_FCM_TOKEN = stringPreferencesKey("ark_synced_fcm_token")
    }

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    override val identity: Flow<ArkIdentity?> = preferences.map { stored ->
        val code = stored[Keys.CODE]
        val deviceToken = stored[Keys.DEVICE_TOKEN]
        if (code.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            null
        } else {
            ArkIdentity(code, stored[Keys.NICKNAME].orEmpty(), deviceToken)
        }
    }

    override suspend fun save(identity: ArkIdentity) {
        dataStore.edit {
            it[Keys.CODE] = identity.code
            it[Keys.NICKNAME] = identity.nickname
            it[Keys.DEVICE_TOKEN] = identity.deviceToken
        }
    }

    override val syncedFcmToken: Flow<String?> =
        preferences.map { it[Keys.SYNCED_FCM_TOKEN]?.takeIf(String::isNotBlank) }

    override suspend fun setSyncedFcmToken(token: String) {
        dataStore.edit { it[Keys.SYNCED_FCM_TOKEN] = token }
    }
}
```

In `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt` add the imports:

```kotlin
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.data.DataStoreArkIdentityRepository
```

and the binding next to `bindArkLinkRepository`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindArkIdentityRepository(
        impl: DataStoreArkIdentityRepository,
    ): ArkIdentityRepository
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/data/ArkIdentityRepository.kt app/src/main/java/org/jarsi/arkphone/di/AppModule.kt app/src/test/java/org/jarsi/arkphone/data/DataStoreArkIdentityRepositoryTest.kt
git commit -m "Store the ARK identity and synced FCM token in DataStore"
```

---

### Task 5: VoIP gateways, warm link cache and the ARK calls view model

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/voip/VoipGateways.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/voip/ArkLinkCache.kt`
- Create: `app/src/main/java/org/jarsi/arkphone/ui/settings/ArkCallsViewModel.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Modify: `app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/settings/ArkCallsViewModelTest.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/voip/ArkLinkCacheTest.kt`

**Interfaces:**
- Consumes: `ArkIdentityRepository`, `ArkIdentity`, `ArkLinkRepository`, `ArkLink`, `arkLinkKey`, `SettingsRepository`.
- Produces: `VoipAccountGateway`, `VoipCallGateway`, `ArkRegistration`, `ArkAccount`, `ArkLinkCache`, `ArkCallsViewModel`, `ArkCallsUiState`; fakes `FakeArkIdentityRepository`, `FakeArkLinkRepository`, `FakeVoipAccountGateway`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/voip/ArkLinkCacheTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArkLinkCacheTest {

    private fun link(number: String, code: String) = ArkLink(
        numberKey = arkLinkKey(number),
        number = number,
        code = code,
        nickname = "Jarsi",
        publicKey = "pk",
        linkedAtMillis = 1_000L,
    )

    @Test
    fun `an unlinked number has no link`() = runTest {
        val repository = FakeArkLinkRepository()
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertNull(cache.linkFor("+358 44 5552841"))
    }

    @Test
    fun `a linked number matches in any spelling`() = runTest {
        val repository = FakeArkLinkRepository()
        repository.state.value = listOf(link("+358 44 5552841", "ARK-7K3M-Q2FP"))
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertEquals("ARK-7K3M-Q2FP", cache.linkFor("044 555 2841")?.code)
    }

    @Test
    fun `a number with no digits never matches`() = runTest {
        val repository = FakeArkLinkRepository()
        repository.state.value = listOf(link("+358 44 5552841", "ARK-7K3M-Q2FP"))
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertNull(cache.linkFor(""))
    }
}
```

Create `app/src/test/java/org/jarsi/arkphone/ui/settings/ArkCallsViewModelTest.kt`:

```kotlin
package org.jarsi.arkphone.ui.settings

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.testing.FakeArkIdentityRepository
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.testing.FakeVoipAccountGateway
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.voip.ArkRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class ArkCallsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val identities = FakeArkIdentityRepository()
    private val settings = FakeSettingsRepository()
    private val gateway = FakeVoipAccountGateway()

    private fun viewModel(available: Boolean = true) = ArkCallsViewModel(
        identityRepository = identities,
        settingsRepository = settings,
        accountGateway = if (available) Optional.of(gateway) else Optional.empty(),
    )

    @Test
    fun `a release build reports the feature as unavailable`() = runTest {
        val model = viewModel(available = false)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertFalse(model.uiState.value.available)
    }

    @Test
    fun `an unregistered device shows no code`() = runTest {
        val model = viewModel()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.available)
        assertNull(model.uiState.value.code)
    }

    @Test
    fun `registering stores the code and the device token`() = runTest {
        gateway.registration = ArkRegistration("ARK-7K3M-Q2FP", "token-abc")
        val model = viewModel()
        model.onNicknameChanged("Jarsi")
        model.onRegister()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"),
            identities.state.value,
        )
        assertEquals("ARK-7K3M-Q2FP", model.uiState.value.code)
        assertFalse(model.uiState.value.registering)
        assertFalse(model.uiState.value.registerFailed)
    }

    @Test
    fun `a blank nickname never reaches the worker`() = runTest {
        val model = viewModel()
        model.onNicknameChanged("   ")
        model.onRegister()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(gateway.registerCalls.isEmpty())
        assertNull(identities.state.value)
    }

    @Test
    fun `a failed registration is shown and changes nothing`() = runTest {
        gateway.registration = null
        val model = viewModel()
        model.onNicknameChanged("Jarsi")
        model.onRegister()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.registerFailed)
        assertNull(identities.state.value)
    }

    @Test
    fun `the master switch is written through`() = runTest {
        val model = viewModel()
        model.onEnabledChanged(false)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertFalse(settings.state.value.arkInternetCallsEnabled)
        assertFalse(model.uiState.value.enabled)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.ArkLinkCacheTest" --tests "org.jarsi.arkphone.ui.settings.ArkCallsViewModelTest"
```

Expected: compilation failure — `Unresolved reference: ArkLinkCache`, `ArkCallsViewModel`, `FakeArkLinkRepository`, `FakeArkIdentityRepository`, `FakeVoipAccountGateway`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/org/jarsi/arkphone/voip/VoipGateways.kt`:

```kotlin
package org.jarsi.arkphone.voip

/** What `POST /register` returns: the ARK code and the one-shot device token. */
data class ArkRegistration(val code: String, val deviceToken: String)

/** What `GET /account/<code>` returns. */
data class ArkAccount(val code: String, val nickname: String, val publicKey: String)

/**
 * Identity and directory operations. Bound only in builds that carry the VoIP
 * engine; a release build resolves `Optional.empty()` and every ARK surface
 * hides itself.
 */
interface VoipAccountGateway {
    /** Registers this device, persists the device token, returns null on failure. */
    suspend fun register(nickname: String): ArkRegistration?

    /** Directory lookup for a code the user typed; null when unknown. */
    suspend fun lookUp(code: String): ArkAccount?
}

/**
 * Outgoing-call handoff. [startCall] returns false when the engine cannot take
 * the call at all, in which case the caller MUST place a carrier call — VoIP
 * never prevents a phone call. When it returns true the engine owns the call
 * and invokes [onFallbackToCarrier] itself if the attempt fails before the
 * peer answers.
 */
interface VoipCallGateway {
    fun startCall(link: ArkLink, onFallbackToCarrier: () -> Unit): Boolean
}
```

Create `app/src/main/java/org/jarsi/arkphone/voip/ArkLinkCache.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warm in-memory copy of the link table for the outgoing-call path, following
 * the SettingsCache pattern: [linkFor] is synchronous so an unlinked number
 * costs nothing, and [await] exists for tests and cold starts. Holding an
 * empty map before the first emission is safe — the worst case is a carrier
 * call, which is exactly the required degradation.
 */
@Singleton
class ArkLinkCache @Inject constructor(
    repository: ArkLinkRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val firstLoad = CompletableDeferred<Unit>()

    private val state = MutableStateFlow<Map<String, ArkLink>>(emptyMap())

    init {
        scope.launch {
            repository.links.collect { links ->
                state.value = links.associateBy { it.numberKey }
                if (!firstLoad.isCompleted) firstLoad.complete(Unit)
            }
        }
    }

    val current: Map<String, ArkLink> get() = state.value

    fun linkFor(number: String): ArkLink? {
        val key = arkLinkKey(number)
        if (key.isEmpty()) return null
        return state.value[key]
    }

    suspend fun await(): Map<String, ArkLink> {
        firstLoad.await()
        return state.value
    }
}
```

Create `app/src/main/java/org/jarsi/arkphone/ui/settings/ArkCallsViewModel.kt`:

```kotlin
package org.jarsi.arkphone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.voip.VoipAccountGateway
import java.util.Optional
import javax.inject.Inject

data class ArkCallsUiState(
    /** False in builds without the VoIP engine: the whole screen hides. */
    val available: Boolean = false,
    val enabled: Boolean = true,
    val code: String? = null,
    val nickname: String = "",
    val registering: Boolean = false,
    val registerFailed: Boolean = false,
)

@HiltViewModel
class ArkCallsViewModel @Inject constructor(
    private val identityRepository: ArkIdentityRepository,
    private val settingsRepository: SettingsRepository,
    private val accountGateway: Optional<VoipAccountGateway>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArkCallsUiState(available = accountGateway.isPresent))
    val uiState: StateFlow<ArkCallsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            identityRepository.identity.collect { identity ->
                _uiState.value = _uiState.value.copy(
                    code = identity?.code,
                    nickname = identity?.nickname ?: _uiState.value.nickname,
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value =
                    _uiState.value.copy(enabled = settings.arkInternetCallsEnabled)
            }
        }
    }

    fun onNicknameChanged(nickname: String) {
        _uiState.value = _uiState.value.copy(nickname = nickname, registerFailed = false)
    }

    fun onEnabledChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled)
        viewModelScope.launch { settingsRepository.setArkInternetCallsEnabled(enabled) }
    }

    fun onRegister() {
        val gateway = accountGateway.orElse(null) ?: return
        val nickname = _uiState.value.nickname.trim()
        // The worker trims and then demands 1..40 characters; a blank nickname
        // would be a guaranteed 400.
        if (nickname.isEmpty() || nickname.length > MAX_NICKNAME_LENGTH) return
        if (_uiState.value.registering) return
        _uiState.value = _uiState.value.copy(registering = true, registerFailed = false)
        viewModelScope.launch {
            val registration = gateway.register(nickname)
            if (registration == null) {
                _uiState.value = _uiState.value.copy(registering = false, registerFailed = true)
                return@launch
            }
            // Persist before anything else: the device token is shown once and
            // is unrecoverable (worker/docs/protocol.md §12 rule 1).
            identityRepository.save(
                ArkIdentity(
                    code = registration.code,
                    nickname = nickname,
                    deviceToken = registration.deviceToken,
                ),
            )
            _uiState.value = _uiState.value.copy(
                registering = false,
                registerFailed = false,
                code = registration.code,
            )
        }
    }

    private companion object {
        const val MAX_NICKNAME_LENGTH = 40
    }
}
```

In `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`, add the imports:

```kotlin
import dagger.BindsOptionalOf
import org.jarsi.arkphone.voip.VoipAccountGateway
import org.jarsi.arkphone.voip.VoipCallGateway
```

and add these two declarations to the abstract class body, next to `bindArkIdentityRepository`:

```kotlin
    // The VoIP engine only exists in builds that carry libwebrtc and Firebase.
    // An optional binding keeps the main tree final while release resolves
    // Optional.empty(), so release gains no dependency and shows nothing.
    @BindsOptionalOf
    abstract fun optionalVoipAccountGateway(): VoipAccountGateway

    @BindsOptionalOf
    abstract fun optionalVoipCallGateway(): VoipCallGateway
```

Append to `app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt`:

```kotlin
class FakeArkIdentityRepository : org.jarsi.arkphone.data.ArkIdentityRepository {
    val state = MutableStateFlow<org.jarsi.arkphone.data.ArkIdentity?>(null)
    val fcmState = MutableStateFlow<String?>(null)
    override val identity: Flow<org.jarsi.arkphone.data.ArkIdentity?> = state
    override suspend fun save(identity: org.jarsi.arkphone.data.ArkIdentity) {
        state.value = identity
    }
    override val syncedFcmToken: Flow<String?> = fcmState
    override suspend fun setSyncedFcmToken(token: String) {
        fcmState.value = token
    }
}

class FakeArkLinkRepository : org.jarsi.arkphone.voip.ArkLinkRepository {
    val state = MutableStateFlow<List<org.jarsi.arkphone.voip.ArkLink>>(emptyList())
    override val links: Flow<List<org.jarsi.arkphone.voip.ArkLink>> = state
    override suspend fun link(
        number: String,
        code: String,
        nickname: String,
        publicKey: String,
        atMillis: Long,
    ) {
        val key = org.jarsi.arkphone.voip.arkLinkKey(number)
        state.value = state.value.filterNot { it.numberKey == key } +
            org.jarsi.arkphone.voip.ArkLink(key, number, code, nickname, publicKey, atMillis)
    }
    override suspend fun unlink(number: String) {
        val key = org.jarsi.arkphone.voip.arkLinkKey(number)
        state.value = state.value.filterNot { it.numberKey == key }
    }
}

class FakeVoipAccountGateway : org.jarsi.arkphone.voip.VoipAccountGateway {
    var registration: org.jarsi.arkphone.voip.ArkRegistration? = null
    var account: org.jarsi.arkphone.voip.ArkAccount? = null
    val registerCalls = mutableListOf<String>()
    val lookUpCalls = mutableListOf<String>()
    override suspend fun register(nickname: String): org.jarsi.arkphone.voip.ArkRegistration? {
        registerCalls += nickname
        return registration
    }
    override suspend fun lookUp(code: String): org.jarsi.arkphone.voip.ArkAccount? {
        lookUpCalls += code
        return account
    }
}

class FakeVoipCallGateway(var accept: Boolean = true) :
    org.jarsi.arkphone.voip.VoipCallGateway {
    val started = mutableListOf<org.jarsi.arkphone.voip.ArkLink>()
    var lastFallback: (() -> Unit)? = null
    var throwOnStart = false
    override fun startCall(
        link: org.jarsi.arkphone.voip.ArkLink,
        onFallbackToCarrier: () -> Unit,
    ): Boolean {
        if (throwOnStart) throw IllegalStateException("engine down")
        started += link
        lastFallback = onFallbackToCarrier
        return accept
    }
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL, both new test classes green.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/voip/VoipGateways.kt app/src/main/java/org/jarsi/arkphone/voip/ArkLinkCache.kt app/src/main/java/org/jarsi/arkphone/ui/settings/ArkCallsViewModel.kt app/src/main/java/org/jarsi/arkphone/di/AppModule.kt app/src/test/java/org/jarsi/arkphone/testing/Fakes.kt app/src/test/java/org/jarsi/arkphone/ui/settings/ArkCallsViewModelTest.kt app/src/test/java/org/jarsi/arkphone/voip/ArkLinkCacheTest.kt
git commit -m "Add VoIP gateway interfaces, link cache and ARK calls view model"
```

---

### Task 6: ARK calls settings screen

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/ui/settings/ArkCallsScreen.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/settings/SettingsActivity.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-fi/strings.xml`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/settings/ArkCallsContentTest.kt`

**Interfaces:**
- Consumes: `ArkCallsUiState`, `ArkCallsViewModel` from Task 5.
- Produces: `ArkCallsContent(...)` and `ArkCallsScreen(...)` composables; `SettingsContent` gains `onOpenArkCalls: () -> Unit = {}` and `arkCallsAvailable: Boolean = false`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/ui/settings/ArkCallsContentTest.kt`:

```kotlin
package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkCallsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anUnregisteredDeviceOffersToCreateACode() {
        composeRule.setContent {
            ArkCallsContent(uiState = ArkCallsUiState(available = true), onBack = {})
        }
        composeRule.onNodeWithText("Create ARK code").assertIsDisplayed()
    }

    @Test
    fun aRegisteredDeviceShowsItsCode() {
        composeRule.setContent {
            ArkCallsContent(
                uiState = ArkCallsUiState(available = true, code = "ARK-7K3M-Q2FP"),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("ARK-7K3M-Q2FP").assertIsDisplayed()
    }

    @Test
    fun aBuildWithoutTheEngineSaysSo() {
        composeRule.setContent {
            ArkCallsContent(uiState = ArkCallsUiState(available = false), onBack = {})
        }
        composeRule.onNodeWithText("ARK internet calls are not available in this build")
            .assertIsDisplayed()
    }

    @Test
    fun theSwitchReportsItsNewValue() {
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            ArkCallsContent(
                uiState = ArkCallsUiState(available = true, enabled = true, code = "ARK-7K3M-Q2FP"),
                onBack = {},
                onEnabledChanged = { changes += it },
            )
        }
        composeRule.onNodeWithText("Use ARK internet calls").performClick()
        assertEquals(listOf(false), changes)
    }

    @Test
    fun aFailedRegistrationIsShown() {
        composeRule.setContent {
            ArkCallsContent(
                uiState = ArkCallsUiState(available = true, registerFailed = true),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Could not create an ARK code. Try again.").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.ui.settings.ArkCallsContentTest"
```

Expected: compilation failure — `Unresolved reference: ArkCallsContent`.

- [ ] **Step 3: Implement**

Add to `app/src/main/res/values/strings.xml`:

```xml
    <string name="settings_ark_calls_title">ARK internet calls</string>
    <string name="settings_ark_calls_description">Your ARK code and linked contacts</string>
    <string name="ark_calls_switch">Use ARK internet calls</string>
    <string name="ark_calls_switch_description">Calls to linked contacts travel over the internet when the other phone is reachable</string>
    <string name="ark_calls_your_code">Your ARK code</string>
    <string name="ark_calls_nickname">Nickname</string>
    <string name="ark_calls_register">Create ARK code</string>
    <string name="ark_calls_registering">Creating…</string>
    <string name="ark_calls_register_failed">Could not create an ARK code. Try again.</string>
    <string name="ark_calls_share">Share code</string>
    <string name="ark_calls_share_text">My ARK code is %1$s</string>
    <string name="ark_calls_unavailable">ARK internet calls are not available in this build</string>
    <string name="ark_calls_linked_contacts">Linked contacts: %1$d</string>
```

Add to `app/src/main/res/values-fi/strings.xml`:

```xml
    <string name="settings_ark_calls_title">ARK-nettipuhelut</string>
    <string name="settings_ark_calls_description">Oma ARK-koodi ja linkitetyt yhteystiedot</string>
    <string name="ark_calls_switch">Käytä ARK-nettipuheluita</string>
    <string name="ark_calls_switch_description">Puhelut linkitettyihin yhteystietoihin kulkevat internetin kautta, kun toinen puhelin on tavoitettavissa</string>
    <string name="ark_calls_your_code">Oma ARK-koodi</string>
    <string name="ark_calls_nickname">Nimimerkki</string>
    <string name="ark_calls_register">Luo ARK-koodi</string>
    <string name="ark_calls_registering">Luodaan…</string>
    <string name="ark_calls_register_failed">ARK-koodin luonti epäonnistui. Yritä uudelleen.</string>
    <string name="ark_calls_share">Jaa koodi</string>
    <string name="ark_calls_share_text">ARK-koodini on %1$s</string>
    <string name="ark_calls_unavailable">ARK-nettipuhelut eivät ole käytössä tässä versiossa</string>
    <string name="ark_calls_linked_contacts">Linkitettyjä yhteystietoja: %1$d</string>
```

Create `app/src/main/java/org/jarsi/arkphone/ui/settings/ArkCallsScreen.kt`:

```kotlin
package org.jarsi.arkphone.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R

@Composable
fun ArkCallsScreen(
    onBack: () -> Unit,
    viewModel: ArkCallsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ArkCallsContent(
        uiState = uiState,
        onBack = onBack,
        onNicknameChanged = viewModel::onNicknameChanged,
        onRegister = viewModel::onRegister,
        onEnabledChanged = viewModel::onEnabledChanged,
        onShare = { code ->
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(R.string.ark_calls_share_text, code),
                )
            runCatching { context.startActivity(Intent.createChooser(share, null)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkCallsContent(
    uiState: ArkCallsUiState,
    onBack: () -> Unit,
    onNicknameChanged: (String) -> Unit = {},
    onRegister: () -> Unit = {},
    onEnabledChanged: (Boolean) -> Unit = {},
    onShare: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_ark_calls_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (!uiState.available) {
                Text(
                    text = stringResource(R.string.ark_calls_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ark_calls_switch),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.ark_calls_switch_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = uiState.enabled, onCheckedChange = onEnabledChanged)
            }
            Text(
                stringResource(R.string.ark_calls_your_code),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            val code = uiState.code
            if (code == null) {
                OutlinedTextField(
                    value = uiState.nickname,
                    onValueChange = onNicknameChanged,
                    singleLine = true,
                    label = { Text(stringResource(R.string.ark_calls_nickname)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.registerFailed) {
                    Text(
                        stringResource(R.string.ark_calls_register_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = onRegister,
                    enabled = !uiState.registering,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(
                        stringResource(
                            if (uiState.registering) {
                                R.string.ark_calls_registering
                            } else {
                                R.string.ark_calls_register
                            },
                        ),
                    )
                }
            } else {
                Text(code, style = MaterialTheme.typography.headlineSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedButton(onClick = { onShare(code) }) {
                        Text(stringResource(R.string.ark_calls_share))
                    }
                }
            }
        }
    }
}
```

In `app/src/main/java/org/jarsi/arkphone/ui/settings/SettingsScreen.kt`:

- add the parameters to `SettingsScreen` after `onOpenBlocking`:

```kotlin
    onOpenArkCalls: () -> Unit = {},
```

- add to `SettingsContent`'s parameter list, after `onOpenBlocking: () -> Unit = {},`:

```kotlin
    onOpenArkCalls: () -> Unit = {},
```

- pass it through in the `SettingsContent(` call inside `SettingsScreen`, next to `onOpenBlocking = onOpenBlocking,`:

```kotlin
        onOpenArkCalls = onOpenArkCalls,
```

- inside `SettingsContent`'s `Column`, immediately after the existing `SettingsLinkRow` that opens blocking (search for `onOpenBlocking` inside the column and place the new row directly below that call), add:

```kotlin
            SettingsLinkRow(
                title = stringResource(R.string.settings_ark_calls_title),
                description = stringResource(R.string.settings_ark_calls_description),
                onClick = onOpenArkCalls,
            )
```

In `app/src/main/java/org/jarsi/arkphone/ui/settings/SettingsActivity.kt`, replace the `setContent` body with:

```kotlin
            ArkPhoneTheme {
                var showSimInfo by rememberSaveable { mutableStateOf(false) }
                var showBlocking by rememberSaveable { mutableStateOf(false) }
                var showArkCalls by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSimInfo || showBlocking || showArkCalls) {
                    showSimInfo = false
                    showBlocking = false
                    showArkCalls = false
                }
                when {
                    showSimInfo -> SimInfoScreen(onBack = { showSimInfo = false })
                    showBlocking -> BlockingScreen(onBack = { showBlocking = false })
                    showArkCalls -> ArkCallsScreen(onBack = { showArkCalls = false })
                    else -> SettingsScreen(
                        onBack = ::finish,
                        onOpenSimInfo = { showSimInfo = true },
                        onOpenBlocking = { showBlocking = true },
                        onOpenArkCalls = { showArkCalls = true },
                    )
                }
            }
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `ArkCallsContentTest` and the existing `SettingsContentTest` green. If lint reports `MissingTranslation`, a Finnish string above was not added.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/ui/settings/ArkCallsScreen.kt app/src/main/java/org/jarsi/arkphone/ui/settings/SettingsActivity.kt app/src/main/java/org/jarsi/arkphone/ui/settings/SettingsScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-fi/strings.xml app/src/test/java/org/jarsi/arkphone/ui/settings/ArkCallsContentTest.kt
git commit -m "Add the ARK internet calls settings screen"
```

---

### Task 7: Contact-card ARK linking

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ArkLinkDialog.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardViewModel.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-fi/strings.xml`
- Test: `app/src/test/java/org/jarsi/arkphone/ui/contactcard/ContactCardArkLinkTest.kt`

**Interfaces:**
- Consumes: `ArkCode.canonicalize`, `ArkLinkRepository`, `ArkLinkCache`, `VoipAccountGateway`, `ArkAccount`, `Clock`, fakes from Task 5.
- Produces: `ContactCardUiState.arkAvailable/arkLink/arkPending/arkError`, `ContactCardViewModel.onArkCodeEntered(code: String)`, `onArkLinkConfirmed()`, `onArkLinkDismissed()`, `onArkUnlink()`, composable `ArkLinkDialog`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/ui/contactcard/ContactCardArkLinkTest.kt`:

```kotlin
package org.jarsi.arkphone.ui.contactcard

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeVoipAccountGateway
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class ContactCardArkLinkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contacts = FakeContactsRepository()
    private val blocked = FakeBlockedNumbersRepository()
    private val links = FakeArkLinkRepository()
    private val gateway = FakeVoipAccountGateway()

    private fun viewModel(available: Boolean = true) = ContactCardViewModel(
        contactsRepository = contacts,
        blockedNumbersRepository = blocked,
        arkLinkRepository = links,
        accountGateway = if (available) Optional.of(gateway) else Optional.empty(),
        clock = Clock { 5_000L },
    )

    private fun givenContact() {
        contacts.detailsById[1L] = ContactDetails(
            id = 1L,
            displayName = "Matti",
            phones = listOf(LabeledField("+358 44 5552841", null)),
        )
    }

    private fun runCurrent() = mainDispatcherRule.dispatcher.scheduler.runCurrent()

    @Test
    fun `a build without the engine hides the ark row`() = runTest {
        givenContact()
        val model = viewModel(available = false)
        model.load(1L)
        runCurrent()
        assertEquals(false, model.uiState.value.arkAvailable)
    }

    @Test
    fun `an invalid code never reaches the worker`() = runTest {
        givenContact()
        val model = viewModel()
        model.load(1L)
        runCurrent()
        model.onArkCodeEntered("not a code")
        runCurrent()
        assertTrue(gateway.lookUpCalls.isEmpty())
        assertEquals(ArkLinkError.INVALID_CODE, model.uiState.value.arkError)
    }

    @Test
    fun `an unknown code is reported`() = runTest {
        givenContact()
        gateway.account = null
        val model = viewModel()
        model.load(1L)
        runCurrent()
        model.onArkCodeEntered("ark-7k3m-q2fp")
        runCurrent()
        assertEquals(listOf("ARK-7K3M-Q2FP"), gateway.lookUpCalls)
        assertEquals(ArkLinkError.NOT_FOUND, model.uiState.value.arkError)
    }

    @Test
    fun `a found account is offered for confirmation and only then stored`() = runTest {
        givenContact()
        gateway.account = ArkAccount("ARK-7K3M-Q2FP", "Jarsi", "pk-test")
        val model = viewModel()
        model.load(1L)
        runCurrent()
        model.onArkCodeEntered("ARK-7K3M-Q2FP")
        runCurrent()
        assertEquals("Jarsi", model.uiState.value.arkPending?.nickname)
        assertTrue(links.state.value.isEmpty())
        model.onArkLinkConfirmed()
        runCurrent()
        val link = links.state.value.single()
        assertEquals("ARK-7K3M-Q2FP", link.code)
        assertEquals("Jarsi", link.nickname)
        assertEquals("pk-test", link.publicKey)
        assertEquals(5_000L, link.linkedAtMillis)
        assertEquals("Jarsi", model.uiState.value.arkLink?.nickname)
        assertNull(model.uiState.value.arkPending)
    }

    @Test
    fun `unlinking removes the row`() = runTest {
        givenContact()
        links.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk", 1_000L)
        val model = viewModel()
        model.load(1L)
        runCurrent()
        assertEquals("Jarsi", model.uiState.value.arkLink?.nickname)
        model.onArkUnlink()
        runCurrent()
        assertTrue(links.state.value.isEmpty())
        assertNull(model.uiState.value.arkLink)
    }
}
```

If `ContactDetails` has required constructor parameters beyond `id`, `displayName` and `phones`, read `app/src/main/java/org/jarsi/arkphone/data/model/ContactDetails.kt` and fill them with their defaults or empty values — do not change the data class.

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.ui.contactcard.ContactCardArkLinkTest"
```

Expected: compilation failure — `ContactCardViewModel` has no such constructor, `Unresolved reference: ArkLinkError`.

- [ ] **Step 3: Implement**

Add to `app/src/main/res/values/strings.xml`:

```xml
    <string name="contact_card_ark_link">Link ARK code</string>
    <string name="contact_card_ark_linked">ARK: %1$s</string>
    <string name="contact_card_ark_unlink">Remove ARK link</string>
    <string name="ark_link_hint">ARK-XXXX-XXXX</string>
    <string name="ark_link_invalid">That is not a valid ARK code</string>
    <string name="ark_link_not_found">No account with that code</string>
    <string name="ark_link_confirm">Link %1$s</string>
```

(insert these lines before the file's existing closing `</resources>` tag — do not add a second one).

Add to `app/src/main/res/values-fi/strings.xml`:

```xml
    <string name="contact_card_ark_link">Linkitä ARK-koodi</string>
    <string name="contact_card_ark_linked">ARK: %1$s</string>
    <string name="contact_card_ark_unlink">Poista ARK-linkitys</string>
    <string name="ark_link_hint">ARK-XXXX-XXXX</string>
    <string name="ark_link_invalid">Tämä ei ole kelvollinen ARK-koodi</string>
    <string name="ark_link_not_found">Koodilla ei löydy tiliä</string>
    <string name="ark_link_confirm">Linkitä %1$s</string>
```

Replace `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardViewModel.kt` with:

```kotlin
package org.jarsi.arkphone.ui.contactcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkAccount
import org.jarsi.arkphone.voip.ArkCode
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.ArkLinkRepository
import org.jarsi.arkphone.voip.VoipAccountGateway
import org.jarsi.arkphone.voip.arkLinkKey
import java.util.Optional
import javax.inject.Inject

enum class ArkLinkError { INVALID_CODE, NOT_FOUND, LOOKUP_FAILED }

data class ContactCardUiState(
    val loading: Boolean = true,
    val details: ContactDetails? = null,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
    /** False in builds without the VoIP engine: the ARK rows never appear. */
    val arkAvailable: Boolean = false,
    val arkLink: ArkLink? = null,
    /** An account fetched for the code the user typed, awaiting confirmation. */
    val arkPending: ArkAccount? = null,
    val arkLookingUp: Boolean = false,
    val arkError: ArkLinkError? = null,
)

@HiltViewModel
class ContactCardViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    private val arkLinkRepository: ArkLinkRepository,
    private val accountGateway: Optional<VoipAccountGateway>,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactCardUiState())
    val uiState: StateFlow<ContactCardUiState> = _uiState.asStateFlow()

    fun load(contactId: Long) {
        viewModelScope.launch {
            val details = contactsRepository.contactDetails(contactId)
            val firstNumber = details?.phones?.firstOrNull()?.value
            _uiState.value = ContactCardUiState(
                loading = false,
                details = details,
                blocked = firstNumber != null && blockedNumbersRepository.isBlocked(firstNumber),
                canBlock = firstNumber != null && blockedNumbersRepository.canBlock(),
                arkAvailable = accountGateway.isPresent && firstNumber != null,
                arkLink = firstNumber?.let { linkFor(it) },
            )
        }
    }

    fun onToggleBlocked() {
        val state = _uiState.value
        val numbers = state.details?.phones?.map { it.value }.orEmpty()
        val firstNumber = numbers.firstOrNull() ?: return
        viewModelScope.launch {
            if (state.blocked) {
                numbers.forEach { blockedNumbersRepository.unblock(it) }
            } else {
                numbers.forEach { blockedNumbersRepository.block(it) }
            }
            // The provider can refuse a change (role lost, one number
            // failing); show its real state instead of the intent.
            _uiState.value = _uiState.value.copy(
                blocked = blockedNumbersRepository.isBlocked(firstNumber),
            )
        }
    }

    /** Validates locally first: a malformed code costs a round trip and a 404. */
    fun onArkCodeEntered(input: String) {
        val gateway = accountGateway.orElse(null) ?: return
        val code = ArkCode.canonicalize(input)
        if (code == null) {
            _uiState.value = _uiState.value.copy(
                arkError = ArkLinkError.INVALID_CODE,
                arkPending = null,
            )
            return
        }
        _uiState.value = _uiState.value.copy(arkLookingUp = true, arkError = null)
        viewModelScope.launch {
            val account = runCatching { gateway.lookUp(code) }.getOrNull()
            _uiState.value = _uiState.value.copy(
                arkLookingUp = false,
                arkPending = account,
                arkError = if (account == null) ArkLinkError.NOT_FOUND else null,
            )
        }
    }

    fun onArkLinkConfirmed() {
        val account = _uiState.value.arkPending ?: return
        val number = _uiState.value.details?.phones?.firstOrNull()?.value ?: return
        viewModelScope.launch {
            arkLinkRepository.link(
                number = number,
                code = account.code,
                nickname = account.nickname,
                publicKey = account.publicKey,
                atMillis = clock.nowMillis(),
            )
            _uiState.value = _uiState.value.copy(
                arkPending = null,
                arkError = null,
                arkLink = linkFor(number),
            )
        }
    }

    fun onArkLinkDismissed() {
        _uiState.value = _uiState.value.copy(arkPending = null, arkError = null)
    }

    fun onArkUnlink() {
        val number = _uiState.value.details?.phones?.firstOrNull()?.value ?: return
        viewModelScope.launch {
            arkLinkRepository.unlink(number)
            _uiState.value = _uiState.value.copy(arkLink = null)
        }
    }

    private suspend fun linkFor(number: String): ArkLink? {
        val key = arkLinkKey(number)
        if (key.isEmpty()) return null
        return arkLinkRepository.links.first().firstOrNull { it.numberKey == key }
    }
}
```

Create `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ArkLinkDialog.kt`:

```kotlin
package org.jarsi.arkphone.ui.contactcard

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jarsi.arkphone.R

/**
 * Enter-or-paste a code, then confirm the nickname the worker returned. The
 * confirmation step is what makes a mistyped code visible before it is stored.
 */
@Composable
fun ArkLinkDialog(
    uiState: ContactCardUiState,
    onCodeEntered: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val pending = uiState.arkPending
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_card_ark_link)) },
        text = {
            if (pending == null) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ark_link_hint)) },
                    isError = uiState.arkError != null,
                    supportingText = {
                        when (uiState.arkError) {
                            ArkLinkError.INVALID_CODE ->
                                Text(
                                    stringResource(R.string.ark_link_invalid),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            ArkLinkError.NOT_FOUND, ArkLinkError.LOOKUP_FAILED ->
                                Text(
                                    stringResource(R.string.ark_link_not_found),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            null -> Unit
                        }
                    },
                )
            } else {
                Text(pending.nickname, style = MaterialTheme.typography.headlineSmall)
            }
        },
        confirmButton = {
            if (pending == null) {
                TextButton(
                    onClick = { onCodeEntered(code) },
                    enabled = !uiState.arkLookingUp,
                ) {
                    Text(stringResource(R.string.contact_card_ark_link))
                }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.ark_link_confirm, pending.nickname))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
```

In `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardScreen.kt`:

- add these parameters to `ContactCardContent`, after `onToggleBlocked: () -> Unit = {},`:

```kotlin
    onArkLink: () -> Unit = {},
    onArkUnlink: () -> Unit = {},
```

- add the same two parameters to `ContactCardDetails`, after `onToggleBlocked: () -> Unit,`:

```kotlin
    onArkLink: () -> Unit,
    onArkUnlink: () -> Unit,
```

- and add `arkAvailable: Boolean,` plus `arkNickname: String?,` to `ContactCardDetails` right after `canBlock: Boolean,`.

- pass them from `ContactCardContent`'s `ContactCardDetails(` call, right after `canBlock = uiState.canBlock,`:

```kotlin
                arkAvailable = uiState.arkAvailable,
                arkNickname = uiState.arkLink?.nickname,
```

and right after `onToggleBlocked = onToggleBlocked,`:

```kotlin
                onArkLink = onArkLink,
                onArkUnlink = onArkUnlink,
```

- inside `ContactCardDetails`, in the last `SectionCard(true) { ... }` block, insert this immediately **before** the `if (firstNumber != null) { ListItem(... contact_card_call_history ...) }` block:

```kotlin
            if (arkAvailable) {
                ListItem(
                    modifier = Modifier.clickableListItem(
                        if (arkNickname == null) onArkLink else onArkUnlink,
                    ),
                    colors = transparentListItemColors(),
                    headlineContent = {
                        Text(
                            if (arkNickname == null) {
                                stringResource(R.string.contact_card_ark_link)
                            } else {
                                stringResource(R.string.contact_card_ark_linked, arkNickname)
                            },
                        )
                    },
                    supportingContent = if (arkNickname == null) {
                        null
                    } else {
                        { Text(stringResource(R.string.contact_card_ark_unlink)) }
                    },
                    leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                )
            }
```

and add `import androidx.compose.material.icons.outlined.Link` to the file's imports.

- in `ContactCardScreen`, add these two arguments to the `ContactCardContent(` call, after `onToggleBlocked = viewModel::onToggleBlocked,`:

```kotlin
        onArkLink = { showArkDialog = true },
        onArkUnlink = viewModel::onArkUnlink,
```

and wrap the body so the dialog can show — replace the `ContactCardScreen` body with:

```kotlin
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showArkDialog by rememberSaveable { mutableStateOf(false) }
    ContactCardContent(
        uiState = uiState,
        onBack = onBack,
        onEdit = { uiState.details?.let(onEdit) },
        onCall = onCall,
        onMessage = onMessage,
        onEmail = onEmail,
        onOpenAddress = onOpenAddress,
        onOpenWebsite = onOpenWebsite,
        onOpenCallHistory = onOpenCallHistory,
        onOpenAppAction = onOpenAppAction,
        onShare = { uiState.details?.let(onShare) },
        onToggleBlocked = viewModel::onToggleBlocked,
        onArkLink = { showArkDialog = true },
        onArkUnlink = viewModel::onArkUnlink,
    )
    if (showArkDialog) {
        ArkLinkDialog(
            uiState = uiState,
            onCodeEntered = viewModel::onArkCodeEntered,
            onConfirm = {
                viewModel.onArkLinkConfirmed()
                showArkDialog = false
            },
            onDismiss = {
                viewModel.onArkLinkDismissed()
                showArkDialog = false
            },
        )
    }
```

adding the imports `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.saveable.rememberSaveable` and `androidx.compose.runtime.setValue` if they are not already present.

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `ContactCardArkLinkTest`, `ContactCardViewModelTest` and `ContactCardContentTest` green. If `ContactCardViewModelTest` fails to compile because of the new constructor parameters, update its construction of `ContactCardViewModel` to pass `FakeArkLinkRepository()`, `Optional.empty()` and `Clock { 0L }`.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/ui/contactcard app/src/main/res/values/strings.xml app/src/main/res/values-fi/strings.xml app/src/test/java/org/jarsi/arkphone/ui/contactcard
git commit -m "Link an ARK code to a contact from the contact card"
```

---

## Stage B2: Engine rebase onto ARK codes, device tokens and FCM

### Task 8: Retire the "ARK VoIP" test screen and the shared token

**Files:**
- Delete: `app/src/debug/java/org/jarsi/arkphone/voip/ui/VoipTestActivity.kt`
- Delete: `app/src/debug/java/org/jarsi/arkphone/voip/ui/VoipTestScreen.kt`
- Delete: `app/src/debug/java/org/jarsi/arkphone/voip/ui/VoipTestViewModel.kt`
- Delete: `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`
- Delete: `app/src/testDebug/java/org/jarsi/arkphone/voip/ui/VoipTestViewModelTest.kt`
- Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `app/src/debug/res/values/strings.xml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Removes `VoipSessionFactory`, `VoipSessionHandles`, `VoipUiState`, `VoipAudioController` and `BuildConfig.VOIP_AUTH_TOKEN`.

- [ ] **Step 1: Write the failing test**

No new test: this task only removes code. The existing suite is the regression net — `SignalingClientTest`, `SignalingMessageTest`, `TurnCredentialsTest` and `WebRtcCallSessionTest` must stay green.

- [ ] **Step 2: Run to verify failure**

Record the current green baseline before deleting anything:

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL. (This is a removal task; the "failure" step is a baseline.)

- [ ] **Step 3: Implement**

Delete the five files listed above:

```
git rm app/src/debug/java/org/jarsi/arkphone/voip/ui/VoipTestActivity.kt app/src/debug/java/org/jarsi/arkphone/voip/ui/VoipTestScreen.kt app/src/debug/java/org/jarsi/arkphone/voip/ui/VoipTestViewModel.kt app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt app/src/testDebug/java/org/jarsi/arkphone/voip/ui/VoipTestViewModelTest.kt
```

Replace `app/src/debug/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- VoIP only: these permissions exist in debug builds and never ship in a
         release APK. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <application>
        <service
            android:name=".voip.VoipForegroundService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
    </application>
</manifest>
```

Replace `app/src/debug/res/values/strings.xml` with only the strings the foreground service still uses:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="voip_notification_channel" translatable="false">ARK internet calls</string>
    <string name="voip_notification_title" translatable="false">ARK call in progress</string>
</resources>
```

In `app/build.gradle.kts`, delete the whole `VOIP_AUTH_TOKEN` `buildConfigField` block so the `debug` build type reads:

```kotlin
        // The VoIP engine reaches the network only from debug builds; the
        // worker URL comes from local.properties, never the repo. Per-device
        // bearer tokens are issued at registration and live in DataStore.
        debug {
            buildConfigField(
                "String",
                "VOIP_WORKER_URL",
                "\"${localProps.getProperty("arkphone.voip.workerUrl") ?: ""}\"",
            )
        }
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL. If lint reports `UnusedResources` for a `voip_*` string, delete that string too.

- [ ] **Step 5: Commit**

```
git add -A app/src/debug app/src/testDebug app/build.gradle.kts
git commit -m "Remove the VoIP test screen and the shared worker token"
```

---

### Task 9: Rebase the signaling client onto codes, device tokens and reach-query

**Files:**
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/SignalingClient.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/SignalingMessage.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/OkHttpWebSocketConnector.kt`
- Modify: `app/src/testDebug/java/org/jarsi/arkphone/voip/SignalingClientTest.kt`

**Interfaces:**
- Consumes: `SignalingMessage`, `SignalingJson`.
- Produces: `WebSocketConnector.connect(url, bearer, onOpen, onText, onClosed)`, `SignalingClient(connector, workerUrl, code, deviceToken, scope)` with `connectionState`, `incoming`, `start()`, `stop()`, `send(message): Boolean`, `suspend fun reach(peer: String, timeoutMs: Long): Boolean`; `SignalingTypes.REACH_QUERY`, `REACH_REPLY`; `SignalingClient.SUPERSEDED_REASON`.

- [ ] **Step 1: Write the failing test**

Replace `app/src/testDebug/java/org/jarsi/arkphone/voip/SignalingClientTest.kt` with:

```kotlin
package org.jarsi.arkphone.voip

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingClientTest {

    private class FakeHandle(var accepts: Boolean = true) : WebSocketHandle {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String): Boolean {
            if (!accepts) return false
            sent.add(text)
            return true
        }
        override fun close() { closed = true }
    }

    private class FakeConnector : WebSocketConnector {
        val handles = mutableListOf<FakeHandle>()
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String>()
        var lastOnOpen: (() -> Unit)? = null
        var lastOnText: ((String) -> Unit)? = null
        var lastOnClosed: ((Int, String) -> Unit)? = null

        override fun connect(
            url: String,
            bearer: String,
            onOpen: () -> Unit,
            onText: (String) -> Unit,
            onClosed: (Int, String) -> Unit,
        ): WebSocketHandle {
            urls += url
            bearers += bearer
            lastOnOpen = onOpen
            lastOnText = onText
            lastOnClosed = onClosed
            return FakeHandle().also { handles.add(it) }
        }

        fun opens() = lastOnOpen!!()
        fun serverSends(message: SignalingMessage) = lastOnText!!(SignalingJson.encode(message))
        fun serverSendsRaw(text: String) = lastOnText!!(text)
    }

    private fun client(connector: FakeConnector, scope: CoroutineScope) =
        SignalingClient(
            connector = connector,
            workerUrl = "https://w",
            code = "ARK-AAAA-AAAA",
            deviceToken = "token-abc",
            scope = scope,
        )

    @Test
    fun `it opens its own inbox with a code dot token bearer`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        assertEquals("https://w/connect/ARK-AAAA-AAAA", connector.urls.single())
        assertEquals("ARK-AAAA-AAAA.token-abc", connector.bearers.single())
        client.stop()
    }

    @Test
    fun `the connection is only reported connected after the handshake`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        assertEquals(SignalingConnectionState.CONNECTING, client.connectionState.value)
        connector.opens()
        assertEquals(SignalingConnectionState.CONNECTED, client.connectionState.value)
        client.stop()
    }

    @Test
    fun `a superseded close is not an error and never reconnects`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.lastOnClosed!!(1000, SignalingClient.SUPERSEDED_REASON)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connector.handles.size)
        client.stop()
    }

    @Test
    fun `any other close reconnects with backoff`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.lastOnClosed!!(1006, "")
        assertEquals(SignalingConnectionState.DISCONNECTED, client.connectionState.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, connector.handles.size)
        connector.lastOnClosed!!(1006, "")
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(3, connector.handles.size)
        client.stop()
    }

    @Test
    fun `the bare pong keepalive never reaches the decoder`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.incoming.test {
            connector.serverSendsRaw("pong")
            connector.serverSends(
                SignalingMessage(
                    type = SignalingTypes.CALL_OFFER,
                    from = "ARK-BBBB-BBBB",
                    payload = buildJsonObject { put("sdp", "v=0") },
                ),
            )
            assertEquals(SignalingTypes.CALL_OFFER, awaitItem().type)
        }
        client.stop()
    }

    @Test
    fun `reach returns true when the peer answers online`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val result = async { client.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        val query = SignalingJson.decode(connector.handles.single().sent.single())!!
        assertEquals(SignalingTypes.REACH_QUERY, query.type)
        assertEquals("ARK-BBBB-BBBB", query.to)
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(result.await())
        client.stop()
    }

    @Test
    fun `a waking reply alone is not reachable and the query times out`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val result = async { client.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject {
                    put("online", false)
                    put("waking", true)
                },
            ),
        )
        advanceTimeBy(4_100)
        runCurrent()
        assertFalse(result.await())
        client.stop()
    }

    @Test
    fun `a late online reply for a waking peer still counts`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val result = async { client.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject {
                    put("online", false)
                    put("waking", true)
                },
            ),
        )
        advanceTimeBy(1_000)
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(result.await())
        client.stop()
    }

    @Test
    fun `a reach reply with no pending query is ignored, not emitted`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.incoming.test {
            connector.serverSends(
                SignalingMessage(
                    type = SignalingTypes.REACH_REPLY,
                    from = "ARK-CCCC-CCCC",
                    payload = buildJsonObject { put("online", true) },
                ),
            )
            connector.serverSends(
                SignalingMessage(type = SignalingTypes.CALL_END, from = "ARK-BBBB-BBBB"),
            )
            assertEquals(SignalingTypes.CALL_END, awaitItem().type)
        }
        client.stop()
    }

    @Test
    fun `a refused send drops the socket and reconnects`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.handles.single().accepts = false
        assertFalse(
            client.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-BBBB-BBBB")),
        )
        assertEquals(SignalingConnectionState.DISCONNECTED, client.connectionState.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, connector.handles.size)
        client.stop()
    }

    @Test
    fun `stop closes the socket and stops reconnecting`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.stop()
        assertTrue(connector.handles.single().closed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connector.handles.size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.SignalingClientTest"
```

Expected: compilation failure — `connect` has the wrong number of parameters, `Unresolved reference: reach`, `SUPERSEDED_REASON`, `REACH_QUERY`.

- [ ] **Step 3: Implement**

Replace the `SignalingTypes` object in `app/src/debug/java/org/jarsi/arkphone/voip/SignalingMessage.kt` with the list below. The legacy `hello` / `presence-query` verbs are dropped on purpose: they report `online: false` in situations where the call would in fact ring, because buffering and the FCM wake happen anyway.

```kotlin
object SignalingTypes {
    const val REACH_QUERY = "reach-query"
    const val REACH_REPLY = "reach-reply"
    const val CALL_OFFER = "call-offer"
    const val CALL_ANSWER = "call-answer"
    const val CALL_REJECT = "call-reject"
    const val ICE_CANDIDATE = "ice-candidate"
    const val CALL_END = "call-end"
    const val ERROR = "error"
}
```

Replace `app/src/debug/java/org/jarsi/arkphone/voip/SignalingClient.kt` with:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Boundary that hides OkHttp so the client logic is unit-testable. */
interface WebSocketConnector {
    /**
     * Opens a socket. [onOpen] fires on the successful handshake, [onText] on
     * every text frame, [onClosed] once with the close code and reason on any
     * terminal close or failure.
     */
    fun connect(
        url: String,
        bearer: String,
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ): WebSocketHandle
}

interface WebSocketHandle {
    fun send(text: String): Boolean
    fun close()
}

enum class SignalingConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * The device's inbox socket. One instance per process: it opens
 * `/connect/<own code>` with the per-device bearer and stays open while the
 * app has anything to do.
 */
class SignalingClient(
    private val connector: WebSocketConnector,
    private val workerUrl: String,
    private val code: String,
    private val deviceToken: String,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<SignalingMessage> = _incoming.asSharedFlow()

    private val pendingReach = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private var handle: WebSocketHandle? = null
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1_000L
    private var running = false

    fun start() {
        if (running) return
        running = true
        open()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        reconnectJob = null
        handle?.close()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
    }

    /** False when the frame could not be handed to a live socket. */
    fun send(message: SignalingMessage): Boolean {
        val sent = handle?.send(SignalingJson.encode(message)) ?: false
        if (!sent) onSendRefused()
        return sent
    }

    /**
     * The routing pre-check. Returns true only on `online: true`, which is
     * terminal; a `waking` reply is not an answer, so the query keeps waiting
     * until [timeoutMs]. A reply for a peer with no pending query is ignored.
     */
    suspend fun reach(peer: String, timeoutMs: Long): Boolean {
        val pending = CompletableDeferred<Boolean>()
        synchronized(pendingReach) { pendingReach[peer] = pending }
        try {
            if (!send(SignalingMessage(type = SignalingTypes.REACH_QUERY, to = peer))) return false
            return withTimeoutOrNull(timeoutMs) { pending.await() } ?: false
        } finally {
            synchronized(pendingReach) { pendingReach.remove(peer) }
        }
    }

    private fun open() {
        _connectionState.value = SignalingConnectionState.CONNECTING
        handle = connector.connect(
            url = "$workerUrl/connect/$code",
            bearer = "$code.$deviceToken",
            onOpen = ::onOpen,
            onText = ::onText,
            onClosed = ::onClosed,
        )
    }

    private fun onOpen() {
        reconnectDelayMs = 1_000L
        _connectionState.value = SignalingConnectionState.CONNECTED
    }

    private fun onText(text: String) {
        // The keepalive answer is the bare string "pong", not JSON.
        if (text == PONG) return
        val message = SignalingJson.decode(text) ?: return
        if (message.type == SignalingTypes.REACH_REPLY) {
            onReachReply(message)
            return
        }
        _incoming.tryEmit(message)
    }

    private fun onReachReply(message: SignalingMessage) {
        val peer = message.from ?: return
        val online = message.payload?.get("online")?.jsonPrimitive?.booleanOrNull ?: return
        if (!online) return
        synchronized(pendingReach) { pendingReach[peer] }?.complete(true)
    }

    private fun onClosed(closeCode: Int, reason: String) {
        // A 1000/"superseded" close means this device opened a newer socket.
        // Reconnecting here would supersede the socket that just replaced this
        // one, and the loop would repeat forever.
        if (closeCode == NORMAL_CLOSE && reason == SUPERSEDED_REASON) return
        if (!running) return
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    /** A send into a half-dead socket is silently lost, so drop and reopen. */
    private fun onSendRefused() {
        if (!running) return
        val lost = handle ?: return
        lost.close()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (running) open()
        }
    }

    companion object {
        const val SUPERSEDED_REASON = "superseded"
        private const val NORMAL_CLOSE = 1000
        private const val PONG = "pong"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
```

Replace `app/src/debug/java/org/jarsi/arkphone/voip/OkHttpWebSocketConnector.kt` with:

```kotlin
package org.jarsi.arkphone.voip

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Real connector: OkHttp WebSocket with the per-device bearer. */
class OkHttpWebSocketConnector(
    private val client: OkHttpClient,
) : WebSocketConnector {

    override fun connect(
        url: String,
        bearer: String,
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ): WebSocketHandle {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    onClosed(FAILURE_CLOSE, t.message.orEmpty())
            },
        )
        return object : WebSocketHandle {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close() { socket.close(NORMAL_CLOSE, null) }
        }
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        // A transport failure is never a "superseded" close, so it must not
        // collide with the reason the client uses to suppress reconnects.
        const val FAILURE_CLOSE = 1006
    }
}
```

`WebRtcCallSession` and its test still use the old `deviceId`/`peerId` wording. Update `WebRtcCallSessionTest`'s fake connector to the new `connect` signature and its `SignalingClient(...)` construction to `SignalingClient(connector, "https://w", "ARK-AAAA-AAAA", "token-abc", this)`. `WebRtcCallSession` itself needs no change: it takes a `peerId: String`, which now carries the peer's ARK code.

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `SignalingClientTest` and `WebRtcCallSessionTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Rebase the signaling client onto ARK codes, device tokens and reach-query"
```

---

### Task 10: Device keypair in the Android Keystore

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/ArkKeys.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/ArkKeysTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun spkiBase64(key: java.security.PublicKey): String`, `interface ArkKeyPairSource { fun publicKeyBase64(): String? }`, `class AndroidKeystoreArkKeyPairSource`, `const val ARK_MAX_PUBLIC_KEY_LENGTH = 200`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/ArkKeysTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkKeysTest {

    private fun p256PublicKey() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair().public

    @Test
    fun anEcP256KeyEncodesInsideTheWorkerCap() {
        val encoded = spkiBase64(p256PublicKey())
        assertTrue("length was ${encoded.length}", encoded.length <= ARK_MAX_PUBLIC_KEY_LENGTH)
        assertTrue("length was ${encoded.length}", encoded.length >= 100)
    }

    @Test
    fun theEncodingCarriesNoPemArmourAndNoLineBreaks() {
        val encoded = spkiBase64(p256PublicKey())
        assertTrue(!encoded.contains("BEGIN"))
        assertTrue(!encoded.contains("\n"))
        assertTrue(!encoded.contains("\r"))
    }

    @Test
    fun theEncodingRoundTripsThroughX509EncodedKeySpec() {
        val key = p256PublicKey()
        val encoded = spkiBase64(key)
        val decoded = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP)))
        assertEquals(key, decoded)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.ArkKeysTest"
```

Expected: compilation failure — `Unresolved reference: spkiBase64`, `ARK_MAX_PUBLIC_KEY_LENGTH`.

- [ ] **Step 3: Implement**

Create `app/src/debug/java/org/jarsi/arkphone/voip/ArkKeys.kt`:

```kotlin
package org.jarsi.arkphone.voip

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/** The worker stores `publicKey` verbatim and caps it at 200 characters. */
const val ARK_MAX_PUBLIC_KEY_LENGTH: Int = 200

/**
 * SPKI DER, plain base64, no PEM header or footer and no line breaks — the
 * form worker/docs/protocol.md section 11 names as the intended one. An EC
 * P-256 key lands around 124 characters, comfortably inside the cap.
 */
fun spkiBase64(key: PublicKey): String = Base64.encodeToString(key.encoded, Base64.NO_WRAP)

/** The device identity key. Created once, never exported, never rotated. */
interface ArkKeyPairSource {
    /** Creates the key on first use; null when the platform refuses. */
    fun publicKeyBase64(): String?
}

class AndroidKeystoreArkKeyPairSource : ArkKeyPairSource {

    override fun publicKeyBase64(): String? = try {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = keyStore.getCertificate(ALIAS)?.publicKey ?: generate()
        spkiBase64(key).takeIf { it.length <= ARK_MAX_PUBLIC_KEY_LENGTH }
    } catch (e: Exception) {
        // Identity without a key is not an identity, but a phone that cannot
        // make one must still place carrier calls.
        Log.w(TAG, "ARK device key unavailable", e)
        null
    }

    private fun generate(): PublicKey {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair().public
    }

    private companion object {
        const val TAG = "ArkPhone"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "ark_device_identity"
    }
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `ArkKeysTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip/ArkKeys.kt app/src/testDebug/java/org/jarsi/arkphone/voip/ArkKeysTest.kt
git commit -m "Create the ARK device keypair in the Android Keystore"
```

---

### Task 11: Worker account client and the account gateway binding

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/ArkHttp.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/ArkAccountClient.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/WorkerVoipAccountGateway.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/TurnCredentials.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/ArkAccountClientTest.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/WorkerVoipAccountGatewayTest.kt`

**Interfaces:**
- Consumes: `ArkRegistration`, `ArkAccount`, `VoipAccountGateway` (Task 5), `ArkIdentityRepository` (Task 4), `ArkKeyPairSource` (Task 10), `TurnCredentialsParser`, `IceServerConfig`, `BuildConfig.VOIP_WORKER_URL`.
- Produces: `data class ArkHttpResponse(statusCode, body)`, `interface ArkHttp`, `class OkHttpArkHttp`, `data class VoipConfig(workerUrl)`, `class ArkAccountClient(http, workerUrl)` with `register/lookUp/updateFcmToken/turnCredentials`, `class WorkerVoipAccountGateway`, Hilt module `VoipModule`, test double `FakeArkHttp`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/ArkAccountClientTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared by every worker-HTTP test in this source set. */
class FakeArkHttp : ArkHttp {
    data class Call(val method: String, val url: String, val body: String?, val bearer: String?)

    val calls = mutableListOf<Call>()
    var response: ArkHttpResponse? = null

    override suspend fun get(url: String, bearer: String?): ArkHttpResponse? {
        calls += Call("GET", url, null, bearer)
        return response
    }

    override suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse? {
        calls += Call("POST", url, json, bearer)
        return response
    }
}

class ArkAccountClientTest {

    private val http = FakeArkHttp()
    private val client = ArkAccountClient(http, "https://w")

    @Test
    fun registrationPostsTheNicknameKeyAndTokenAndReadsTheCodeBack() = runTest {
        http.response = ArkHttpResponse(200, """{"code":"ARK-7K3M-Q2FP","deviceToken":"tok"}""")
        val registration = client.register("Jarsi", "pk-test", "fcm-1")
        assertEquals(ArkRegistration("ARK-7K3M-Q2FP", "tok"), registration)
        val call = http.calls.single()
        assertEquals("https://w/register", call.url)
        assertNull(call.bearer)
        assertTrue(call.body!!.contains("\"nickname\":\"Jarsi\""))
        assertTrue(call.body.contains("\"publicKey\":\"pk-test\""))
        assertTrue(call.body.contains("\"fcmToken\":\"fcm-1\""))
    }

    @Test
    fun anAbsentFcmTokenIsOmittedRatherThanSentAsAnEmptyString() = runTest {
        http.response = ArkHttpResponse(200, """{"code":"ARK-7K3M-Q2FP","deviceToken":"tok"}""")
        client.register("Jarsi", "pk-test", null)
        assertFalse(http.calls.single().body!!.contains("fcmToken"))
    }

    @Test
    fun aRejectedOrRateLimitedRegistrationIsAFailureNotACrash() = runTest {
        http.response = ArkHttpResponse(429, "Rate limited")
        assertNull(client.register("Jarsi", "pk-test", null))
        http.response = ArkHttpResponse(400, "Bad request")
        assertNull(client.register("Jarsi", "pk-test", null))
        http.response = null
        assertNull(client.register("Jarsi", "pk-test", null))
    }

    @Test
    fun aLookupUsesTheBearerAndReturnsTheNicknameAndKey() = runTest {
        http.response = ArkHttpResponse(
            200,
            """{"code":"ARK-BBBB-BBBB","nickname":"B","publicKey":"pk-b"}""",
        )
        val account = client.lookUp("ARK-BBBB-BBBB", "ARK-AAAA-AAAA.tok")
        assertEquals(ArkAccount("ARK-BBBB-BBBB", "B", "pk-b"), account)
        val call = http.calls.single()
        assertEquals("https://w/account/ARK-BBBB-BBBB", call.url)
        assertEquals("ARK-AAAA-AAAA.tok", call.bearer)
    }

    @Test
    fun anUnregisteredCodeIsA404AndYieldsNull() = runTest {
        http.response = ArkHttpResponse(404, "")
        assertNull(client.lookUp("ARK-BBBB-BBBB", "ARK-AAAA-AAAA.tok"))
    }

    @Test
    fun anFcmTokenUpdateIsA204() = runTest {
        http.response = ArkHttpResponse(204, "")
        assertTrue(client.updateFcmToken("fcm-2", "ARK-AAAA-AAAA.tok"))
        val call = http.calls.single()
        assertEquals("https://w/account/fcm-token", call.url)
        assertEquals("""{"fcmToken":"fcm-2"}""", call.body)
    }

    @Test
    fun aRejectedFcmTokenUpdateIsFalse() = runTest {
        http.response = ArkHttpResponse(400, "Bad request")
        assertFalse(client.updateFcmToken("fcm-2", "ARK-AAAA-AAAA.tok"))
    }

    @Test
    fun turnCredentialsAreParsedFromTheProxiedBody() = runTest {
        http.response = ArkHttpResponse(
            200,
            """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]}]}""",
        )
        val servers = client.turnCredentials("ARK-AAAA-AAAA.tok")
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), servers!!.single().urls)
        assertEquals("https://w/turn-credentials", http.calls.single().url)
    }

    @Test
    fun anUnavailableTurnUpstreamYieldsNull() = runTest {
        http.response = ArkHttpResponse(502, "TURN credentials unavailable")
        assertNull(client.turnCredentials("ARK-AAAA-AAAA.tok"))
    }
}
```

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/WorkerVoipAccountGatewayTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared by every identity-backed test in this source set. */
class TestArkIdentityRepository(identity: ArkIdentity? = null) : ArkIdentityRepository {
    val state = MutableStateFlow(identity)
    val fcm = MutableStateFlow<String?>(null)
    override val identity: Flow<ArkIdentity?> = state
    override suspend fun save(identity: ArkIdentity) { state.value = identity }
    override val syncedFcmToken: Flow<String?> = fcm
    override suspend fun setSyncedFcmToken(token: String) { fcm.value = token }
}

class TestArkKeyPairSource(private val key: String?) : ArkKeyPairSource {
    override fun publicKeyBase64(): String? = key
}

class WorkerVoipAccountGatewayTest {

    private val http = FakeArkHttp()
    private val identities = TestArkIdentityRepository()

    private fun gateway(key: String? = "pk-test") = WorkerVoipAccountGateway(
        accountClient = ArkAccountClient(http, "https://w"),
        identityRepository = identities,
        keyPairSource = TestArkKeyPairSource(key),
    )

    @Test
    fun registrationSendsTheDevicePublicKey() = runTest {
        http.response = ArkHttpResponse(200, """{"code":"ARK-7K3M-Q2FP","deviceToken":"tok"}""")
        val registration = gateway().register("Jarsi")
        assertEquals(ArkRegistration("ARK-7K3M-Q2FP", "tok"), registration)
        assertTrue(http.calls.single().body!!.contains("pk-test"))
    }

    @Test
    fun withoutADeviceKeyThereIsNoRegistration() = runTest {
        assertNull(gateway(key = null).register("Jarsi"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun aLookupBeforeRegistrationIsImpossible() = runTest {
        assertNull(gateway().lookUp("ARK-BBBB-BBBB"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun aLookupAfterRegistrationUsesTheStoredBearer() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        http.response = ArkHttpResponse(
            200,
            """{"code":"ARK-BBBB-BBBB","nickname":"B","publicKey":"pk-b"}""",
        )
        assertEquals(ArkAccount("ARK-BBBB-BBBB", "B", "pk-b"), gateway().lookUp("ARK-BBBB-BBBB"))
        assertEquals("ARK-AAAA-AAAA.tok", http.calls.single().bearer)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.ArkAccountClientTest" --tests "org.jarsi.arkphone.voip.WorkerVoipAccountGatewayTest"
```

Expected: compilation failure — `Unresolved reference: ArkHttp`, `ArkAccountClient`, `WorkerVoipAccountGateway`.

- [ ] **Step 3: Implement**

Create `app/src/debug/java/org/jarsi/arkphone/voip/ArkHttp.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Where the signaling worker lives; supplied from local.properties. */
data class VoipConfig(val workerUrl: String)

data class ArkHttpResponse(val statusCode: Int, val body: String)

/** Boundary around OkHttp so the worker protocol is unit-testable. */
interface ArkHttp {
    /** Null means the request never completed — no status, no body. */
    suspend fun get(url: String, bearer: String?): ArkHttpResponse?

    suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse?
}

class OkHttpArkHttp(private val client: OkHttpClient) : ArkHttp {

    override suspend fun get(url: String, bearer: String?): ArkHttpResponse? =
        execute(Request.Builder().url(url).get(), bearer)

    override suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse? =
        execute(Request.Builder().url(url).post(json.toRequestBody(JSON)), bearer)

    private suspend fun execute(builder: Request.Builder, bearer: String?): ArkHttpResponse? =
        withContext(Dispatchers.IO) {
            try {
                bearer?.let { builder.header("Authorization", "Bearer $it") }
                client.newCall(builder.build()).execute().use { response ->
                    ArkHttpResponse(response.code, response.body.string())
                }
            } catch (_: Exception) {
                null
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/ArkAccountClient.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The worker's HTTP surface, exactly as worker/docs/protocol.md describes it.
 * Every failure is a null or false — this layer never throws at its callers,
 * because any uncertainty on the VoIP path has to degrade to a carrier call.
 */
class ArkAccountClient(
    private val http: ArkHttp,
    private val workerUrl: String,
) {
    /** `POST /register`, the only unauthenticated route. */
    suspend fun register(
        nickname: String,
        publicKey: String,
        fcmToken: String?,
    ): ArkRegistration? {
        val body = buildJsonObject {
            put("nickname", nickname)
            put("publicKey", publicKey)
            // A device registered with an empty token can never be woken, and
            // the worker accepts "" — so omit the field instead of sending one.
            if (!fcmToken.isNullOrBlank()) put("fcmToken", fcmToken)
        }
        val response = http.postJson("$workerUrl/register", body.toString(), bearer = null)
        if (response == null || response.statusCode != OK) return null
        val json = runCatching {
            SignalingJson.json.parseToJsonElement(response.body).jsonObject
        }.getOrNull() ?: return null
        val code = (json["code"] as? JsonPrimitive)?.content ?: return null
        val deviceToken = (json["deviceToken"] as? JsonPrimitive)?.content ?: return null
        return ArkRegistration(code, deviceToken)
    }

    /** `GET /account/<code>` — the only way to prove an account exists. */
    suspend fun lookUp(code: String, bearer: String): ArkAccount? {
        val response = http.get("$workerUrl/account/$code", bearer)
        if (response == null || response.statusCode != OK) return null
        val json = runCatching {
            SignalingJson.json.parseToJsonElement(response.body).jsonObject
        }.getOrNull() ?: return null
        return ArkAccount(
            code = (json["code"] as? JsonPrimitive)?.content ?: return null,
            nickname = (json["nickname"] as? JsonPrimitive)?.content ?: return null,
            publicKey = (json["publicKey"] as? JsonPrimitive)?.content ?: return null,
        )
    }

    /** `POST /account/fcm-token` — 204 on success; there is no clear route. */
    suspend fun updateFcmToken(fcmToken: String, bearer: String): Boolean {
        if (fcmToken.isBlank()) return false
        val body = buildJsonObject { put("fcmToken", fcmToken) }
        val response = http.postJson("$workerUrl/account/fcm-token", body.toString(), bearer)
        return response?.statusCode == NO_CONTENT
    }

    /** `GET /turn-credentials` — short-lived, so fetched per call attempt. */
    suspend fun turnCredentials(bearer: String): List<IceServerConfig>? {
        val response = http.get("$workerUrl/turn-credentials", bearer)
        if (response == null || response.statusCode != OK) return null
        return TurnCredentialsParser.parse(response.body)
    }

    private companion object {
        const val OK = 200
        const val NO_CONTENT = 204
    }
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/WorkerVoipAccountGateway.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.data.ArkIdentityRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerVoipAccountGateway @Inject constructor(
    private val accountClient: ArkAccountClient,
    private val identityRepository: ArkIdentityRepository,
    private val keyPairSource: ArkKeyPairSource,
) : VoipAccountGateway {

    override suspend fun register(nickname: String): ArkRegistration? {
        // No key, no identity: the account is key-bound from day one.
        val publicKey = keyPairSource.publicKeyBase64() ?: return null
        val fcmToken = identityRepository.syncedFcmToken.first()
        return accountClient.register(nickname, publicKey, fcmToken)
    }

    override suspend fun lookUp(code: String): ArkAccount? {
        val identity = identityRepository.identity.first() ?: return null
        return accountClient.lookUp(code, "${identity.code}.${identity.deviceToken}")
    }
}
```

In `app/src/debug/java/org/jarsi/arkphone/voip/TurnCredentials.kt`, delete the `TurnCredentialsFetcher` class entirely — `ArkAccountClient.turnCredentials` replaces it — and keep `IceServerConfig` and `TurnCredentialsParser` unchanged. Remove the now-unused `okhttp3.OkHttpClient`, `okhttp3.Request`, `kotlinx.coroutines.Dispatchers` and `kotlinx.coroutines.withContext` imports. If `TurnCredentialsTest` exercises `TurnCredentialsFetcher`, delete only those test methods and keep the parser tests.

Create `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`:

```kotlin
package org.jarsi.arkphone.voip.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.voip.AndroidKeystoreArkKeyPairSource
import org.jarsi.arkphone.voip.ArkAccountClient
import org.jarsi.arkphone.voip.ArkHttp
import org.jarsi.arkphone.voip.ArkKeyPairSource
import org.jarsi.arkphone.voip.OkHttpArkHttp
import org.jarsi.arkphone.voip.OkHttpWebSocketConnector
import org.jarsi.arkphone.voip.VoipAccountGateway
import org.jarsi.arkphone.voip.VoipConfig
import org.jarsi.arkphone.voip.WebSocketConnector
import org.jarsi.arkphone.voip.WorkerVoipAccountGateway
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Debug-only: this module is what fills the main tree's optional VoIP
 * bindings. A release build has no such module, so every ARK surface resolves
 * Optional.empty() and stays invisible.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoipModule {

    @Provides
    @Singleton
    fun provideVoipOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideVoipConfig(): VoipConfig = VoipConfig(BuildConfig.VOIP_WORKER_URL)

    @Provides
    @Singleton
    fun provideArkHttp(client: OkHttpClient): ArkHttp = OkHttpArkHttp(client)

    @Provides
    @Singleton
    fun provideArkAccountClient(http: ArkHttp, config: VoipConfig): ArkAccountClient =
        ArkAccountClient(http, config.workerUrl)

    @Provides
    @Singleton
    fun provideArkKeyPairSource(): ArkKeyPairSource = AndroidKeystoreArkKeyPairSource()

    @Provides
    @Singleton
    fun provideWebSocketConnector(client: OkHttpClient): WebSocketConnector =
        OkHttpWebSocketConnector(client)

    @Provides
    @Singleton
    fun provideVoipAccountGateway(impl: WorkerVoipAccountGateway): VoipAccountGateway = impl
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL. In a debug build the ARK calls settings screen can now register, and the contact-card link row can look a code up.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Add the worker account client and bind the ARK account gateway"
```

---

### Task 12: Process-lifetime VoIP engine with flush reconciliation

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/FlushReconciler.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/VoipEngine.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/FlushReconcilerTest.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/VoipEngineTest.kt`

**Interfaces:**
- Consumes: `SignalingClient`, `WebSocketConnector`, `VoipConfig`, `ArkIdentityRepository`, `SignalingMessage`, `SignalingTypes`, `TestArkIdentityRepository` (Task 11).
- Produces: `data class IncomingArkCall(fromCode, offerSdp)`, `fun reconcileFlush(messages: List<SignalingMessage>): IncomingArkCall?`, `const val FLUSH_DRAIN_MS: Long`, `@Singleton class VoipEngine(identityRepository, connector, config, scope)` with `fun onWake()`, `suspend fun connect(): Boolean`, `suspend fun reach(peerCode: String, timeoutMs: Long): Boolean`, `fun send(message: SignalingMessage): Boolean`, `val incomingCalls: SharedFlow<IncomingArkCall>`, `val signals: SharedFlow<SignalingMessage>`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/FlushReconcilerTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlushReconcilerTest {

    private fun offer(from: String, sdp: String) = SignalingMessage(
        type = SignalingTypes.CALL_OFFER,
        from = from,
        payload = buildJsonObject { put("sdp", sdp) },
    )

    private fun end(from: String) =
        SignalingMessage(type = SignalingTypes.CALL_END, from = from)

    private fun reject(from: String) =
        SignalingMessage(type = SignalingTypes.CALL_REJECT, from = from)

    @Test
    fun anEmptyFlushRingsForNobody() {
        assertNull(reconcileFlush(emptyList()))
    }

    @Test
    fun aLoneOfferRings() {
        assertEquals(
            IncomingArkCall("ARK-BBBB-BBBB", "v=0"),
            reconcileFlush(listOf(offer("ARK-BBBB-BBBB", "v=0"))),
        )
    }

    @Test
    fun anOfferAlreadyFollowedByItsEndNeverRings() {
        assertNull(reconcileFlush(listOf(offer("ARK-BBBB-BBBB", "v=0"), end("ARK-BBBB-BBBB"))))
    }

    @Test
    fun aCancelledAttemptDoesNotCancelALaterOneFromTheSamePeer() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 first"),
            end("ARK-BBBB-BBBB"),
            offer("ARK-BBBB-BBBB", "v=0 second"),
        )
        assertEquals(IncomingArkCall("ARK-BBBB-BBBB", "v=0 second"), reconcileFlush(flush))
    }

    @Test
    fun withTwoCallersTheNewestSurvivingOfferWins() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 b"),
            offer("ARK-CCCC-CCCC", "v=0 c"),
        )
        assertEquals(IncomingArkCall("ARK-CCCC-CCCC", "v=0 c"), reconcileFlush(flush))
    }

    @Test
    fun aCancelledNewestOfferFallsBackToTheSurvivingOlderOne() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 b"),
            offer("ARK-CCCC-CCCC", "v=0 c"),
            reject("ARK-CCCC-CCCC"),
        )
        assertEquals(IncomingArkCall("ARK-BBBB-BBBB", "v=0 b"), reconcileFlush(flush))
    }

    @Test
    fun framesWithoutAServerAttestedFromAreIgnored() {
        val flush = listOf(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                payload = buildJsonObject { put("sdp", "v=0") },
            ),
            SignalingMessage(type = SignalingTypes.ICE_CANDIDATE, from = "ARK-BBBB-BBBB"),
        )
        assertNull(reconcileFlush(flush))
    }
}
```

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/VoipEngineTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarsi.arkphone.data.ArkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoipEngineTest {

    private class StubHandle : WebSocketHandle {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() = Unit
    }

    private class EngineConnector : WebSocketConnector {
        val handles = mutableListOf<StubHandle>()
        var lastOnOpen: (() -> Unit)? = null
        var lastOnText: ((String) -> Unit)? = null
        override fun connect(
            url: String,
            bearer: String,
            onOpen: () -> Unit,
            onText: (String) -> Unit,
            onClosed: (Int, String) -> Unit,
        ): WebSocketHandle {
            lastOnOpen = onOpen
            lastOnText = onText
            return StubHandle().also { handles += it }
        }
        fun opens() = lastOnOpen!!()
        fun serverSends(message: SignalingMessage) = lastOnText!!(SignalingJson.encode(message))
    }

    private fun engine(
        connector: EngineConnector,
        scope: CoroutineScope,
        identity: ArkIdentity? = ArkIdentity("ARK-AAAA-AAAA", "A", "tok"),
    ) = VoipEngine(
        identityRepository = TestArkIdentityRepository(identity),
        connector = connector,
        config = VoipConfig("https://w"),
        scope = scope,
    )

    private fun offer(from: String, sdp: String) = SignalingMessage(
        type = SignalingTypes.CALL_OFFER,
        from = from,
        payload = buildJsonObject { put("sdp", sdp) },
    )

    @Test
    fun anUnregisteredDeviceNeverOpensASocket() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope, identity = null)
        assertFalse(engine.connect())
        assertTrue(connector.handles.isEmpty())
    }

    @Test
    fun connectOpensTheInboxOnceHoweverOftenItIsCalled() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        val first = async { engine.connect() }
        runCurrent()
        connector.opens()
        assertTrue(first.await())
        assertTrue(engine.connect())
        assertEquals(1, connector.handles.size)
    }

    @Test
    fun theFlushIsDrainedBeforeAnythingRings() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        engine.incomingCalls.test {
            val connecting = async { engine.connect() }
            runCurrent()
            connector.opens()
            connecting.await()
            connector.serverSends(offer("ARK-BBBB-BBBB", "v=0 b"))
            connector.serverSends(
                SignalingMessage(type = SignalingTypes.CALL_END, from = "ARK-BBBB-BBBB"),
            )
            connector.serverSends(offer("ARK-CCCC-CCCC", "v=0 c"))
            expectNoEvents()
            advanceTimeBy(FLUSH_DRAIN_MS + 100)
            runCurrent()
            assertEquals(IncomingArkCall("ARK-CCCC-CCCC", "v=0 c"), awaitItem())
        }
    }

    @Test
    fun anOfferArrivingAfterTheDrainRingsStraightAway() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        val connecting = async { engine.connect() }
        runCurrent()
        connector.opens()
        connecting.await()
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        engine.incomingCalls.test {
            connector.serverSends(offer("ARK-BBBB-BBBB", "v=0 late"))
            assertEquals(IncomingArkCall("ARK-BBBB-BBBB", "v=0 late"), awaitItem())
        }
    }

    @Test
    fun reachReportsAPeerThatAnswersOnline() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        val connecting = async { engine.connect() }
        runCurrent()
        connector.opens()
        connecting.await()
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        val reachable = async { engine.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(reachable.await())
    }

    @Test
    fun reachOnAnUnregisteredDeviceIsFalseNotAnException() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope, identity = null)
        assertFalse(engine.reach("ARK-BBBB-BBBB", 4_000))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.FlushReconcilerTest" --tests "org.jarsi.arkphone.voip.VoipEngineTest"
```

Expected: compilation failure — `Unresolved reference: reconcileFlush`, `VoipEngine`, `FLUSH_DRAIN_MS`.

- [ ] **Step 3: Implement**

Create `app/src/debug/java/org/jarsi/arkphone/voip/FlushReconciler.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.serialization.json.jsonPrimitive

/** A call that should ring on this device. */
data class IncomingArkCall(val fromCode: String, val offerSdp: String)

/**
 * The inbox flushes every message queued for this account in the last 30 s,
 * oldest first, from every caller. The burst can therefore contain a complete
 * stale attempt, several callers, or an offer already followed by its end.
 * Reduce it to at most one call before ringing: the newest offer that has not
 * been cancelled by a later end or reject from the same peer.
 */
fun reconcileFlush(messages: List<SignalingMessage>): IncomingArkCall? {
    val live = LinkedHashMap<String, String>()
    for (message in messages) {
        // `from` is server-attested; a frame without one is not a real call.
        val from = message.from ?: continue
        when (message.type) {
            SignalingTypes.CALL_OFFER -> {
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: continue
                live.remove(from)
                live[from] = sdp
            }
            SignalingTypes.CALL_END, SignalingTypes.CALL_REJECT -> live.remove(from)
            else -> Unit
        }
    }
    val newest = live.entries.lastOrNull() ?: return null
    return IncomingArkCall(newest.key, newest.value)
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/VoipEngine.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/** How long the buffered flush is collected before anything is allowed to ring. */
const val FLUSH_DRAIN_MS: Long = 500L

/** How long connecting may take before the inbox is treated as unreachable. */
private const val CONNECT_TIMEOUT_MS = 8_000L

/**
 * The one long-lived piece of VoIP state in the process: this device's inbox
 * socket, the flush reconciliation that decides what rings, and the reach
 * pre-check the routing branch uses.
 */
@Singleton
class VoipEngine @Inject constructor(
    private val identityRepository: ArkIdentityRepository,
    private val connector: WebSocketConnector,
    private val config: VoipConfig,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val connectMutex = Mutex()

    private var client: SignalingClient? = null

    private var draining = false
    private val drained = mutableListOf<SignalingMessage>()

    private val _incomingCalls = MutableSharedFlow<IncomingArkCall>(extraBufferCapacity = 8)
    val incomingCalls: SharedFlow<IncomingArkCall> = _incomingCalls.asSharedFlow()

    private val _signals = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val signals: SharedFlow<SignalingMessage> = _signals.asSharedFlow()

    /** Called from the FCM wake path; connecting is all a wake ever does. */
    fun onWake() {
        scope.launch { connect() }
    }

    /** True once the inbox socket is open. False when this device has no identity. */
    suspend fun connect(): Boolean {
        val active = connectMutex.withLock {
            val identity = identityRepository.identity.first() ?: return false
            client ?: SignalingClient(
                connector = connector,
                workerUrl = config.workerUrl,
                code = identity.code,
                deviceToken = identity.deviceToken,
                scope = scope,
            ).also { created ->
                client = created
                startCollecting(created)
                created.start()
            }
        }
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            active.connectionState.first { it == SignalingConnectionState.CONNECTED }
            true
        } ?: false
    }

    /** The routing pre-check; false whenever anything at all is uncertain. */
    suspend fun reach(peerCode: String, timeoutMs: Long): Boolean {
        if (!connect()) return false
        val active = client ?: return false
        return active.reach(peerCode, timeoutMs)
    }

    fun send(message: SignalingMessage): Boolean = client?.send(message) ?: false

    private fun startCollecting(created: SignalingClient) {
        scope.launch {
            created.connectionState.collect { state ->
                if (state == SignalingConnectionState.CONNECTED) beginDrain()
            }
        }
        scope.launch {
            created.incoming.collect { message ->
                if (draining) drained += message else dispatch(message)
            }
        }
    }

    private fun beginDrain() {
        if (draining) return
        draining = true
        drained.clear()
        scope.launch {
            delay(FLUSH_DRAIN_MS)
            val batch = drained.toList()
            drained.clear()
            draining = false
            batch.forEach { _signals.tryEmit(it) }
            reconcileFlush(batch)?.let { _incomingCalls.tryEmit(it) }
        }
    }

    private fun dispatch(message: SignalingMessage) {
        _signals.tryEmit(message)
        reconcileFlush(listOf(message))?.let { _incomingCalls.tryEmit(it) }
    }
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `FlushReconcilerTest` and `VoipEngineTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Add the process-lifetime VoIP engine with flush reconciliation"
```

---

### Task 13: FCM wake-up client and Firebase build wiring

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/fcm/FcmTokenSync.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/fcm/ArkMessagingService.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/fcm/ArkFcmRegistration.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `.gitignore`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/fcm/FcmTokenSyncTest.kt`

**Interfaces:**
- Consumes: `ArkIdentityRepository`, `ArkAccountClient`, `VoipEngine`, `FakeArkHttp`, `TestArkIdentityRepository`.
- Produces: `class FcmTokenSync(identityRepository, accountClient)` with `suspend fun sync(token: String): Boolean`, `class ArkFcmRegistration(context, tokenSync, scope)` with `fun refresh()`, `class ArkMessagingService : FirebaseMessagingService`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/fcm/FcmTokenSyncTest.kt`:

```kotlin
package org.jarsi.arkphone.voip.fcm

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.voip.ArkAccountClient
import org.jarsi.arkphone.voip.ArkHttpResponse
import org.jarsi.arkphone.voip.FakeArkHttp
import org.jarsi.arkphone.voip.TestArkIdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmTokenSyncTest {

    private val http = FakeArkHttp()
    private val identities = TestArkIdentityRepository()
    private val sync = FcmTokenSync(identities, ArkAccountClient(http, "https://w"))

    @Test
    fun anUnregisteredDeviceOnlyRemembersTheTokenLocally() = runTest {
        assertFalse(sync.sync("fcm-1"))
        assertTrue(http.calls.isEmpty())
        assertEquals("fcm-1", identities.fcm.value)
    }

    @Test
    fun aRegisteredDevicePostsTheTokenAndRemembersIt() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        http.response = ArkHttpResponse(204, "")
        assertTrue(sync.sync("fcm-1"))
        assertEquals("https://w/account/fcm-token", http.calls.single().url)
        assertEquals("ARK-AAAA-AAAA.tok", http.calls.single().bearer)
        assertEquals("fcm-1", identities.fcm.value)
    }

    @Test
    fun anUnchangedTokenIsNotPostedAgain() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        identities.fcm.value = "fcm-1"
        assertTrue(sync.sync("fcm-1"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun anEmptyTokenIsNeverSent() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        assertFalse(sync.sync("   "))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun aRejectedPostIsNotRememberedAsSynced() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        http.response = ArkHttpResponse(400, "Bad request")
        assertFalse(sync.sync("fcm-1"))
        assertNull(identities.fcm.value)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.fcm.FcmTokenSyncTest"
```

Expected: compilation failure — `Unresolved reference: FcmTokenSync`.

- [ ] **Step 3: Implement**

In `gradle/libs.versions.toml` add to `[versions]`:

```toml
googleServices = "4.5.0"
firebaseMessaging = "25.1.1"
```

to `[libraries]`:

```toml
firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging", version.ref = "firebaseMessaging" }
```

and to `[plugins]`:

```toml
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

In the root `build.gradle.kts` add to the `plugins` block:

```kotlin
    alias(libs.plugins.google.services) apply false
```

In `app/build.gradle.kts`:

- add immediately after the `plugins { ... }` block:

```kotlin
// google-services.json carries the Firebase project identity and is not in
// this public repo. The plugin is applied only when the file is present, the
// same way the release signing config exists only with ARKPHONE_STORE_FILE —
// a checkout without it still builds, just without push wake-up.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
```

- add to `dependencies`, next to the other `debugImplementation` lines:

```kotlin
    debugImplementation(libs.firebase.messaging)
```

In `.gitignore`, add under the `# Keystores` block:

```
# Firebase project identity (public repo)
app/google-services.json
```

In `app/src/debug/AndroidManifest.xml`, add inside `<application>` after the existing `<service>`:

```xml
        <service
            android:name=".voip.fcm.ArkMessagingService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/fcm/FcmTokenSync.kt`:

```kotlin
package org.jarsi.arkphone.voip.fcm

import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.voip.ArkAccountClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the worker's copy of this device's FCM registration token current. A
 * peer registered with no token can never be woken, so the token is posted as
 * soon as an identity exists and again whenever Firebase rotates it.
 */
@Singleton
class FcmTokenSync @Inject constructor(
    private val identityRepository: ArkIdentityRepository,
    private val accountClient: ArkAccountClient,
) {
    /** True when the worker holds [token]. */
    suspend fun sync(token: String): Boolean {
        if (token.isBlank()) return false
        val identity = identityRepository.identity.first()
        if (identity == null) {
            // Registration has not happened yet; remember the token so the
            // registration call itself can carry it.
            identityRepository.setSyncedFcmToken(token)
            return false
        }
        if (identityRepository.syncedFcmToken.first() == token) return true
        val posted = accountClient.updateFcmToken(
            token,
            "${identity.code}.${identity.deviceToken}",
        )
        if (posted) identityRepository.setSyncedFcmToken(token)
        return posted
    }
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/fcm/ArkFcmRegistration.kt`:

```kotlin
package org.jarsi.arkphone.voip.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Firebase for the current registration token and hands it to the worker.
 * A checkout without google-services.json has no initialized FirebaseApp, so
 * this is a no-op there rather than a crash.
 */
@Singleton
class ArkFcmRegistration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenSync: FcmTokenSync,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun refresh() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.i(TAG, "No Firebase configuration; push wake-up is off")
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = task.result
            if (!task.isSuccessful || token.isNullOrBlank()) {
                Log.w(TAG, "FCM token unavailable", task.exception)
                return@addOnCompleteListener
            }
            scope.launch { tokenSync.sync(token) }
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/fcm/ArkMessagingService.kt`:

```kotlin
package org.jarsi.arkphone.voip.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.voip.VoipEngine
import javax.inject.Inject

/**
 * The wake-up path. The push is data-only and carries
 * `{type:"incoming-call", from:<code>}`, but `from` names only the first
 * caller inside the worker's 10 s per-target cooldown — a second caller inside
 * that window produces no push at all. The payload is therefore a wake signal
 * and nothing more: who is actually calling comes from the buffered messages
 * the inbox flushes on connect.
 */
@AndroidEntryPoint
class ArkMessagingService : FirebaseMessagingService() {

    @Inject lateinit var engine: VoipEngine

    @Inject lateinit var tokenSync: FcmTokenSync

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != TYPE_INCOMING_CALL) return
        Log.i(TAG, "Wake push received; connecting the inbox")
        engine.onWake()
    }

    override fun onNewToken(token: String) {
        appScope.launch { tokenSync.sync(token) }
    }

    private companion object {
        const val TAG = "ArkPhone"
        const val TYPE_INCOMING_CALL = "incoming-call"
    }
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL, `FcmTokenSyncTest` green. The build must also succeed with `app/google-services.json` absent — the plugin block is guarded by `file("google-services.json").exists()`, so confirm the gate passes before the file is added to the working copy.

- [ ] **Step 5: Commit**

```
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts .gitignore app/src/debug/AndroidManifest.xml app/src/debug/java/org/jarsi/arkphone/voip/fcm app/src/testDebug/java/org/jarsi/arkphone/voip/fcm
git commit -m "Add the FCM wake-up client and Firebase build wiring"
```

---

## Stage B3: The Telecom call — one in-call UI, call log, timeouts

### Task 14: core-telecom dependency and the VoIP call handle

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallHandle.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `app/src/main/java/org/jarsi/arkphone/telecom/CallHandle.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/telecom/CallController.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/incall/CallScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-fi/strings.xml`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/VoipCallHandleTest.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/telecom/CallControllerTest.kt`

**Interfaces:**
- Consumes: `CallHandle`, `CallInfo`, `VoipCallState` (`Idle`/`Connecting`/`Ringing(offerSdp)`/`InCall`/`Ended(reason)`), `Clock`.
- Produces: `CallHandle.viaArkCall: Boolean` (default `false`), `CallInfo.viaArkCall: Boolean` (default `false`), `enum class VoipCallDirection { INCOMING, OUTGOING }`, `interface VoipCallActions { fun answer(); fun reject(); fun hangUp() }`, `class VoipCallHandle(id, number, displayName, direction, actions, clock)` with `fun onState(state: VoipCallState)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/VoipCallHandleTest.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import android.telecom.Call
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoipCallHandleTest {

    private class RecordingActions : VoipCallActions {
        val calls = mutableListOf<String>()
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangUp() { calls += "hangUp" }
    }

    private val actions = RecordingActions()

    private fun handle(direction: VoipCallDirection, now: Long = 7_000L) = VoipCallHandle(
        id = "voip-1",
        number = "+358 44 5552841",
        displayName = "Jarsi",
        direction = direction,
        actions = actions,
        clock = Clock { now },
    )

    @Test
    fun anOutgoingCallStartsAsConnectingThenDials() {
        val handle = handle(VoipCallDirection.OUTGOING)
        assertEquals(Call.STATE_CONNECTING, handle.telecomState)
        handle.onState(VoipCallState.Connecting)
        assertEquals(Call.STATE_DIALING, handle.telecomState)
    }

    @Test
    fun anIncomingCallRingsFromTheStart() {
        val handle = handle(VoipCallDirection.INCOMING)
        assertEquals(Call.STATE_RINGING, handle.telecomState)
        handle.onState(VoipCallState.Ringing("v=0"))
        assertEquals(Call.STATE_RINGING, handle.telecomState)
        handle.onState(VoipCallState.Connecting)
        assertEquals(Call.STATE_CONNECTING, handle.telecomState)
    }

    @Test
    fun theCallBecomesActiveAndStampsItsConnectTime() {
        val handle = handle(VoipCallDirection.OUTGOING)
        assertEquals(0L, handle.connectTimeMillis)
        handle.onState(VoipCallState.InCall)
        assertEquals(Call.STATE_ACTIVE, handle.telecomState)
        assertEquals(7_000L, handle.connectTimeMillis)
    }

    @Test
    fun theConnectTimeIsStampedOnceAndSurvivesTheHangUp() {
        val handle = handle(VoipCallDirection.OUTGOING)
        handle.onState(VoipCallState.InCall)
        handle.onState(VoipCallState.Ended("local-hangup"))
        assertEquals(Call.STATE_DISCONNECTED, handle.telecomState)
        assertEquals(7_000L, handle.connectTimeMillis)
    }

    @Test
    fun aCallThatEndedBeforeItConnectedHasNoConnectTime() {
        val handle = handle(VoipCallDirection.OUTGOING)
        handle.onState(VoipCallState.Ended("no-answer"))
        assertEquals(0L, handle.connectTimeMillis)
    }

    @Test
    fun theHandleIsMarkedAsAnArkCallAndCarriesNoSim() {
        val handle = handle(VoipCallDirection.INCOMING)
        assertTrue(handle.viaArkCall)
        assertEquals(null, handle.simAccountId)
        assertEquals(null, handle.disconnectError)
    }

    @Test
    fun theControlActionsReachTheSession() {
        val handle = handle(VoipCallDirection.INCOMING)
        handle.answer()
        handle.reject()
        handle.disconnect()
        assertEquals(listOf("answer", "reject", "hangUp"), actions.calls)
    }

    @Test
    fun holdAndDtmfAreInertInPhaseOne() {
        val handle = handle(VoipCallDirection.INCOMING)
        handle.hold()
        handle.unhold()
        handle.playDtmf('5')
        handle.stopDtmf()
        assertTrue(actions.calls.isEmpty())
    }
}
```

Add to the existing `app/src/test/java/org/jarsi/arkphone/telecom/CallControllerTest.kt` (keep every existing test; follow the file's own helper for building a fake `CallHandle` — if it has one, add `viaArkCall` as a constructor parameter defaulting to `false`):

```kotlin
    @Test
    fun anArkCallIsPublishedAsOne() {
        val controller = CallController()
        controller.onCallAdded(
            object : CallHandle {
                override val id = "voip-1"
                override val number = "+358 44 5552841"
                override val displayName = "Jarsi"
                override val telecomState = android.telecom.Call.STATE_ACTIVE
                override val connectTimeMillis = 1_000L
                override val simAccountId: String? = null
                override val viaArkCall = true
                override fun answer() = Unit
                override fun reject() = Unit
                override fun disconnect() = Unit
                override fun hold() = Unit
                override fun unhold() = Unit
                override fun playDtmf(digit: Char) = Unit
                override fun stopDtmf() = Unit
            },
        )
        assertTrue(controller.calls.value.single().viaArkCall)
    }
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.telecom.VoipCallHandleTest" --tests "org.jarsi.arkphone.telecom.CallControllerTest"
```

Expected: compilation failure — `Unresolved reference: VoipCallHandle`, `viaArkCall`.

- [ ] **Step 3: Implement**

In `gradle/libs.versions.toml` add to `[versions]`:

```toml
coreTelecom = "1.0.1"
```

and to `[libraries]`:

```toml
androidx-core-telecom = { group = "androidx.core", name = "core-telecom", version.ref = "coreTelecom" }
```

In `app/build.gradle.kts` add next to the other `debugImplementation` lines:

```kotlin
    debugImplementation(libs.androidx.core.telecom)
```

In `app/src/debug/AndroidManifest.xml`, add these two permissions after `FOREGROUND_SERVICE_MICROPHONE` and widen the service type:

```xml
    <uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
```

```xml
        <service
            android:name=".voip.VoipForegroundService"
            android:exported="false"
            android:foregroundServiceType="phoneCall|microphone" />
```

(`core-telecom`'s own manifest already contributes `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT`, the `JetpackConnectionService` and a mute-state receiver into the debug merge; declaring `MANAGE_OWN_CALLS` here keeps it visible beside the other VoIP permissions. `FOREGROUND_SERVICE_PHONE_CALL` is granted because ARK holds the default-dialer role and `MANAGE_OWN_CALLS`.)

In `app/src/main/java/org/jarsi/arkphone/telecom/CallHandle.kt`:

- add to `interface CallHandle`, right after `disconnectError`:

```kotlin
    /** True for a call carried over the internet rather than the carrier. */
    val viaArkCall: Boolean get() = false
```

- add to `data class CallInfo`, after `disconnectError`:

```kotlin
    val viaArkCall: Boolean = false,
```

In `app/src/main/java/org/jarsi/arkphone/telecom/CallController.kt`, add to `infoOf`, after `disconnectError = handle.disconnectError,`:

```kotlin
        viaArkCall = handle.viaArkCall,
```

Add to `app/src/main/res/values/strings.xml`:

```xml
    <string name="incall_ark_call">ARK call</string>
```

Add to `app/src/main/res/values-fi/strings.xml`:

```xml
    <string name="incall_ark_call">ARK-puhelu</string>
```

In `app/src/main/java/org/jarsi/arkphone/ui/incall/CallScreen.kt`, insert directly after the existing `uiState.simLabel?.let { sim -> ... }` block:

```kotlin
                if (call?.viaArkCall == true) {
                    Text(
                        text = stringResource(R.string.incall_ark_call),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallHandle.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import android.telecom.Call
import org.jarsi.arkphone.telecom.CallHandle
import org.jarsi.arkphone.telecom.DisconnectError
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState

enum class VoipCallDirection { INCOMING, OUTGOING }

/** What the in-call UI's buttons do to the media session. */
interface VoipCallActions {
    fun answer()
    fun reject()
    fun hangUp()
}

/**
 * A VoIP call seen through the interface ARK's existing call UI already
 * consumes, so InCallActivity, the notifications and the Google task model
 * work on it unchanged.
 */
class VoipCallHandle(
    override val id: String,
    override val number: String?,
    override val displayName: String?,
    private val direction: VoipCallDirection,
    private val actions: VoipCallActions,
    private val clock: Clock,
) : CallHandle {

    var state: VoipCallState = VoipCallState.Idle
        private set

    override var connectTimeMillis: Long = 0L
        private set

    /** Calls over the internet never belong to a SIM. */
    override val simAccountId: String? = null

    /** Nothing in the VoIP path produces a platform DisconnectCause. */
    override val disconnectError: DisconnectError? = null

    override val viaArkCall: Boolean = true

    override val telecomState: Int
        get() = when (state) {
            VoipCallState.Idle ->
                if (direction == VoipCallDirection.INCOMING) {
                    Call.STATE_RINGING
                } else {
                    Call.STATE_CONNECTING
                }
            VoipCallState.Connecting ->
                if (direction == VoipCallDirection.INCOMING) {
                    // The user answered; media is still being set up.
                    Call.STATE_CONNECTING
                } else {
                    Call.STATE_DIALING
                }
            is VoipCallState.Ringing -> Call.STATE_RINGING
            VoipCallState.InCall -> Call.STATE_ACTIVE
            is VoipCallState.Ended -> Call.STATE_DISCONNECTED
        }

    fun onState(state: VoipCallState) {
        this.state = state
        // Stamped once: the call duration must survive the disconnect so the
        // ended screen and the call-log row agree on it.
        if (state == VoipCallState.InCall && connectTimeMillis == 0L) {
            connectTimeMillis = clock.nowMillis()
        }
    }

    override fun answer() = actions.answer()
    override fun reject() = actions.reject()
    override fun disconnect() = actions.hangUp()

    // Phase 1 ARK calls have no hold and no in-band DTMF; the buttons stay
    // inert rather than pretending to do something.
    override fun hold() = Unit
    override fun unhold() = Unit
    override fun playDtmf(digit: Char) = Unit
    override fun stopDtmf() = Unit
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `VoipCallHandleTest`, `CallControllerTest` and `CallScreenTest` green.

- [ ] **Step 5: Commit**

```
git add gradle/libs.versions.toml app/build.gradle.kts app/src/debug app/src/main/java/org/jarsi/arkphone/telecom app/src/main/java/org/jarsi/arkphone/ui/incall/CallScreen.kt app/src/main/res app/src/test/java/org/jarsi/arkphone/telecom/CallControllerTest.kt app/src/testDebug/java/org/jarsi/arkphone/voip/telecom
git commit -m "Add core-telecom and the VoIP call handle"
```

---

### Task 15: Media session seam onto the engine socket

**Files:**
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/WebRtcCallSession.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/EngineSignaling.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/SignalingClient.kt`
- Modify: `app/src/testDebug/java/org/jarsi/arkphone/voip/WebRtcCallSessionTest.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/EngineSignalingTest.kt`

**Interfaces:**
- Consumes: `VoipEngine.signals`, `VoipEngine.send`, `SignalingClient`, `SignalingMessage`, `WebRtcCallSession`.
- Produces: `interface CallSignaling { val incoming: SharedFlow<SignalingMessage>; fun send(message: SignalingMessage): Boolean }` (implemented by `SignalingClient`), `class EngineSignaling(engine: VoipEngine) : CallSignaling`, `interface VoipMediaSession` implemented by `WebRtcCallSession`, `fun interface VoipMediaSessionFactory { fun create(peerCode: String, offerSdp: String?, scope: CoroutineScope): VoipMediaSession }`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/EngineSignalingTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarsi.arkphone.data.ArkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineSignalingTest {

    private class StubHandle : WebSocketHandle {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() = Unit
    }

    private class StubConnector : WebSocketConnector {
        val handles = mutableListOf<StubHandle>()
        var lastOnOpen: (() -> Unit)? = null
        var lastOnText: ((String) -> Unit)? = null
        override fun connect(
            url: String,
            bearer: String,
            onOpen: () -> Unit,
            onText: (String) -> Unit,
            onClosed: (Int, String) -> Unit,
        ): WebSocketHandle {
            lastOnOpen = onOpen
            lastOnText = onText
            return StubHandle().also { handles += it }
        }
    }

    @Test
    fun signalsFromTheEngineReachTheCallSession() = runTest {
        val connector = StubConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val signaling = EngineSignaling(engine)
        val connecting = async { engine.connect() }
        runCurrent()
        connector.lastOnOpen!!()
        connecting.await()
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        signaling.incoming.test {
            connector.lastOnText!!(
                SignalingJson.encode(
                    SignalingMessage(
                        type = SignalingTypes.CALL_ANSWER,
                        from = "ARK-BBBB-BBBB",
                        payload = buildJsonObject { put("sdp", "v=0") },
                    ),
                ),
            )
            assertEquals(SignalingTypes.CALL_ANSWER, awaitItem().type)
        }
    }

    @Test
    fun sendsGoOutThroughTheEngineSocket() = runTest {
        val connector = StubConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val signaling = EngineSignaling(engine)
        assertFalse(signaling.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-B")))
        val connecting = async { engine.connect() }
        runCurrent()
        connector.lastOnOpen!!()
        connecting.await()
        assertTrue(signaling.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-B")))
        assertEquals(1, connector.handles.single().sent.size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.EngineSignalingTest"
```

Expected: compilation failure — `Unresolved reference: EngineSignaling`.

- [ ] **Step 3: Implement**

Create `app/src/debug/java/org/jarsi/arkphone/voip/EngineSignaling.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What one call needs from the signaling layer. The socket itself belongs to
 * the engine and outlives every call, so a call session only ever borrows it.
 */
interface CallSignaling {
    val incoming: SharedFlow<SignalingMessage>
    fun send(message: SignalingMessage): Boolean
}

/** Adapts the process-wide engine socket to one call's view of it. */
class EngineSignaling(private val engine: VoipEngine) : CallSignaling {
    override val incoming: SharedFlow<SignalingMessage> get() = engine.signals
    override fun send(message: SignalingMessage): Boolean = engine.send(message)
}

/** The media half of a call, as the coordinator drives it. */
interface VoipMediaSession {
    val state: StateFlow<VoipCallState>
    fun placeCall()
    fun answer()
    fun reject()
    fun hangUp()
}

fun interface VoipMediaSessionFactory {
    /** [offerSdp] is null for an outgoing call. */
    fun create(peerCode: String, offerSdp: String?, scope: CoroutineScope): VoipMediaSession
}
```

In `app/src/debug/java/org/jarsi/arkphone/voip/SignalingClient.kt`, change the class declaration to implement the new interface and mark the two members as overrides:

```kotlin
class SignalingClient(
    private val connector: WebSocketConnector,
    private val workerUrl: String,
    private val code: String,
    private val deviceToken: String,
    private val scope: CoroutineScope,
) : CallSignaling {
```

```kotlin
    override val incoming: SharedFlow<SignalingMessage> = _incoming.asSharedFlow()
```

```kotlin
    /** False when the frame could not be handed to a live socket. */
    override fun send(message: SignalingMessage): Boolean {
```

In `app/src/debug/java/org/jarsi/arkphone/voip/WebRtcCallSession.kt`:

- change the constructor's `signaling` type and implement `VoipMediaSession`:

```kotlin
class WebRtcCallSession(
    private val signaling: CallSignaling,
    private val adapterFactory: PeerConnectionAdapterFactory,
    private val turnFetcher: suspend () -> List<IceServerConfig>?,
    private val scope: CoroutineScope,
    private val peerId: String,
    initialOfferSdp: String? = null,
) : VoipMediaSession {
```

- mark the four control functions and `state` as overrides:

```kotlin
    override val state: StateFlow<VoipCallState> = _state.asStateFlow()
```

```kotlin
    override fun placeCall() {
```

```kotlin
    override fun answer() {
```

```kotlin
    override fun reject() {
```

```kotlin
    override fun hangUp() {
```

- and seed an incoming call's offer, so a session created from a flushed offer is already ringing. Add this as the first statement of the existing `init` block, before the `scope.launch { ... }`:

```kotlin
        if (initialOfferSdp != null) _state.value = VoipCallState.Ringing(initialOfferSdp)
```

Update `app/src/testDebug/java/org/jarsi/arkphone/voip/WebRtcCallSessionTest.kt` so its fake signaling implements `CallSignaling` instead of constructing a `SignalingClient`:

```kotlin
    private class FakeSignaling : CallSignaling {
        val sent = mutableListOf<SignalingMessage>()
        private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
        override val incoming: SharedFlow<SignalingMessage> = _incoming
        override fun send(message: SignalingMessage): Boolean {
            sent += message
            return true
        }
        suspend fun serverSends(message: SignalingMessage) { _incoming.emit(message) }
    }
```

and replace every place the old test built a `SignalingClient` plus a fake connector with this `FakeSignaling`, asserting on `sent` instead of on the encoded socket frames. Keep every existing assertion about offers, answers, candidate buffering, reject, hangup and errors.

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `EngineSignalingTest` and `WebRtcCallSessionTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Give call sessions a signaling seam onto the engine socket"
```

---

### Task 16: Call coordinator, Telecom registration and the 15 s connect timeout

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipTelecom.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinator.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/CoreTelecomRegistrar.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/CallControllerVoipCallUi.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/VoipForegroundService.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinatorTest.kt`

**Interfaces:**
- Consumes: `VoipEngine`, `VoipCallHandle`, `VoipCallDirection`, `VoipCallActions`, `VoipMediaSession`, `VoipMediaSessionFactory`, `VoipCallGateway`, `ArkLink`, `ArkLinkCache`, `Clock`, `CallController`, `CallNotifications`, `InCallActivity`.
- Produces: `interface VoipTelecom { fun add(handle, onSystemAnswer, onSystemDisconnect): Boolean; fun setActive(id: String); fun remove(id: String) }`, `interface VoipCallUi { fun added(handle); fun changed(); fun removed(id); fun showIncoming(handle); fun showOngoing(handle); fun clearNotification(); fun openCallScreen(); fun startCallService(); fun stopCallService() }`, `@Singleton class VoipCallCoordinator : VoipCallGateway` with `fun onIncoming(call: IncomingArkCall)`, `const val VOIP_CONNECT_TIMEOUT_MS = 15_000L`, `const val VOIP_REACH_TIMEOUT_MS = 4_000L`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinatorTest.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.IncomingArkCall
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.VoipMediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoipCallCoordinatorTest {

    private class FakeSession : VoipMediaSession {
        val calls = mutableListOf<String>()
        private val _state = MutableStateFlow<VoipCallState>(VoipCallState.Idle)
        override val state: StateFlow<VoipCallState> = _state
        override fun placeCall() { calls += "placeCall" }
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangUp() { calls += "hangUp" }
        fun moveTo(next: VoipCallState) { _state.value = next }
    }

    private class FakeTelecom(var accepts: Boolean = true) : VoipTelecom {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var lastAnswer: (() -> Unit)? = null
        var lastDisconnect: (() -> Unit)? = null
        override fun add(
            handle: VoipCallHandle,
            onSystemAnswer: () -> Unit,
            onSystemDisconnect: () -> Unit,
        ): Boolean {
            if (!accepts) return false
            added += handle.id
            lastAnswer = onSystemAnswer
            lastDisconnect = onSystemDisconnect
            return true
        }
        override fun setActive(id: String) = Unit
        override fun remove(id: String) { removed += id }
    }

    private class FakeUi : VoipCallUi {
        val events = mutableListOf<String>()
        override fun added(handle: VoipCallHandle) { events += "added" }
        override fun changed() { events += "changed" }
        override fun removed(id: String) { events += "removed" }
        override fun showIncoming(handle: VoipCallHandle) { events += "showIncoming" }
        override fun showOngoing(handle: VoipCallHandle) { events += "showOngoing" }
        override fun clearNotification() { events += "clearNotification" }
        override fun openCallScreen() { events += "openCallScreen" }
        override fun startCallService() { events += "startCallService" }
        override fun stopCallService() { events += "stopCallService" }
    }

    private class FakeReach(var reachable: Boolean) {
        val queries = mutableListOf<String>()
        suspend fun reach(code: String, timeoutMs: Long): Boolean {
            queries += code
            return reachable
        }
    }

    private val session = FakeSession()
    private val telecom = FakeTelecom()
    private val ui = FakeUi()

    private val link = ArkLink(
        numberKey = "445552841",
        number = "+358 44 5552841",
        code = "ARK-BBBB-BBBB",
        nickname = "Jarsi",
        publicKey = "pk",
        linkedAtMillis = 1_000L,
    )

    private fun coordinator(
        scope: CoroutineScope,
        reach: FakeReach,
        nicknameFor: (String) -> String? = { "Jarsi" },
        numberFor: (String) -> String? = { "+358 44 5552841" },
    ) = VoipCallCoordinator(
        reachCheck = { code, timeout -> reach.reach(code, timeout) },
        sessionFactory = { _, _, _ -> session },
        telecom = telecom,
        ui = ui,
        nicknameForCode = nicknameFor,
        numberForCode = numberFor,
        clock = Clock { 1_000L },
        scope = scope,
    )

    @Test
    fun aRefusingPlatformMeansTheCallerMustUseTheCarrier() = runTest {
        telecom.accepts = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        assertFalse(coordinator.startCall(link) { })
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun anUnreachablePeerFallsBackToTheCarrierWithoutRinging() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = false))
        assertTrue(coordinator.startCall(link) { fellBack = true })
        advanceUntilIdle()
        assertTrue(fellBack)
        assertTrue(session.calls.isEmpty())
        assertEquals(listOf("voip-out-ARK-BBBB-BBBB"), telecom.removed)
    }

    @Test
    fun aReachablePeerGetsAnOfferAndTheCallScreenOpens() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        assertTrue(coordinator.startCall(link) { })
        advanceUntilIdle()
        assertEquals(listOf("placeCall"), session.calls)
        assertTrue(ui.events.contains("added"))
        assertTrue(ui.events.contains("openCallScreen"))
    }

    @Test
    fun aCallThatNeverConnectsFallsBackAtFifteenSeconds() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        advanceUntilIdle()
        assertFalse(fellBack)
        advanceTimeBy(VOIP_CONNECT_TIMEOUT_MS + 100)
        runCurrent()
        assertTrue(fellBack)
        assertTrue(session.calls.contains("hangUp"))
        assertTrue(ui.events.contains("removed"))
    }

    @Test
    fun aConnectedCallIsNotTornDownByTheTimeout() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        advanceUntilIdle()
        session.moveTo(VoipCallState.InCall)
        advanceTimeBy(VOIP_CONNECT_TIMEOUT_MS + 100)
        runCurrent()
        assertFalse(fellBack)
        assertTrue(ui.events.contains("startCallService"))
    }

    @Test
    fun aDeclinedCallDoesNotFallBackToTheCarrier() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        advanceUntilIdle()
        session.moveTo(VoipCallState.Ended("rejected"))
        advanceUntilIdle()
        assertFalse(fellBack)
        assertTrue(ui.events.contains("removed"))
    }

    @Test
    fun anIncomingCallRingsThroughTheExistingNotification() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        advanceUntilIdle()
        assertTrue(ui.events.contains("showIncoming"))
        assertTrue(ui.events.contains("added"))
        assertEquals(listOf("voip-in-ARK-BBBB-BBBB"), telecom.added)
    }

    @Test
    fun aSecondCallWhileOneIsUpIsRefused() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { }
        advanceUntilIdle()
        assertFalse(coordinator.startCall(link) { })
    }

    @Test
    fun theSystemAnswerAndDisconnectReachTheSession() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        advanceUntilIdle()
        telecom.lastAnswer!!()
        telecom.lastDisconnect!!()
        assertEquals(listOf("answer", "hangUp"), session.calls)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.telecom.VoipCallCoordinatorTest"
```

Expected: compilation failure — `Unresolved reference: VoipCallCoordinator`, `VoipTelecom`, `VoipCallUi`, `VOIP_CONNECT_TIMEOUT_MS`.

- [ ] **Step 3: Implement**

Create `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipTelecom.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

/**
 * Platform registration for a self-managed call: audio focus, Bluetooth and
 * speaker routing, volume keys and coexistence with carrier calls all come
 * from Telecom once the call is added.
 */
interface VoipTelecom {
    /** False when the platform refuses the call — the caller must not proceed. */
    fun add(
        handle: VoipCallHandle,
        onSystemAnswer: () -> Unit,
        onSystemDisconnect: () -> Unit,
    ): Boolean

    fun setActive(id: String)

    fun remove(id: String)
}

/** What the coordinator needs from ARK's existing call surfaces. */
interface VoipCallUi {
    fun added(handle: VoipCallHandle)
    fun changed()
    fun removed(id: String)
    fun showIncoming(handle: VoipCallHandle)
    fun showOngoing(handle: VoipCallHandle)
    fun clearNotification()
    fun openCallScreen()
    fun startCallService()
    fun stopCallService()
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinator.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.IncomingArkCall
import org.jarsi.arkphone.voip.VoipCallGateway
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.VoipMediaSession
import org.jarsi.arkphone.voip.VoipMediaSessionFactory

/** The reach-query budget before the call goes out over the carrier instead. */
const val VOIP_REACH_TIMEOUT_MS: Long = 4_000L

/** How long a VoIP attempt may sit unanswered before the carrier takes over. */
const val VOIP_CONNECT_TIMEOUT_MS: Long = 15_000L

/**
 * Owns the one VoIP call this phone can have at a time and drives every
 * surface it touches. The rule the whole design serves: any uncertainty on
 * this path degrades to a carrier call, never to silence.
 */
class VoipCallCoordinator(
    private val reachCheck: suspend (code: String, timeoutMs: Long) -> Boolean,
    private val sessionFactory: VoipMediaSessionFactory,
    private val telecom: VoipTelecom,
    private val ui: VoipCallUi,
    private val nicknameForCode: (String) -> String?,
    private val numberForCode: (String) -> String?,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : VoipCallGateway {

    private var active: ActiveCall? = null

    private class ActiveCall(
        val handle: VoipCallHandle,
        val session: VoipMediaSession,
        val onFallbackToCarrier: (() -> Unit)?,
        var stateJob: Job? = null,
        var timeoutJob: Job? = null,
        var answered: Boolean = false,
    )

    override fun startCall(link: ArkLink, onFallbackToCarrier: () -> Unit): Boolean {
        if (active != null) return false
        val id = "voip-out-${link.code}"
        val session = sessionFactory.create(link.code, null, scope)
        val handle = VoipCallHandle(
            id = id,
            number = link.number,
            displayName = link.nickname,
            direction = VoipCallDirection.OUTGOING,
            actions = actionsFor(session),
            clock = clock,
        )
        if (!telecom.add(handle, onSystemAnswer = { session.answer() }, onSystemDisconnect = { session.hangUp() })) {
            return false
        }
        val call = ActiveCall(handle, session, onFallbackToCarrier)
        active = call
        ui.added(handle)
        ui.openCallScreen()
        observe(call)
        scope.launch {
            // The screen is already up; the pre-check runs behind it.
            val reachable = runCatching { reachCheck(link.code, VOIP_REACH_TIMEOUT_MS) }
                .getOrDefault(false)
            if (active !== call) return@launch
            if (!reachable) {
                fallBack(call)
                return@launch
            }
            session.placeCall()
            armConnectTimeout(call)
        }
        return true
    }

    /** A call reconciled out of the inbox flush. */
    fun onIncoming(call: IncomingArkCall) {
        if (active != null) return
        val id = "voip-in-${call.fromCode}"
        val session = sessionFactory.create(call.fromCode, call.offerSdp, scope)
        val handle = VoipCallHandle(
            id = id,
            number = numberForCode(call.fromCode),
            displayName = nicknameForCode(call.fromCode),
            direction = VoipCallDirection.INCOMING,
            actions = actionsFor(session),
            clock = clock,
        )
        if (!telecom.add(handle, onSystemAnswer = { session.answer() }, onSystemDisconnect = { session.hangUp() })) {
            return
        }
        val activeCall = ActiveCall(handle, session, onFallbackToCarrier = null)
        active = activeCall
        ui.added(handle)
        ui.showIncoming(handle)
        observe(activeCall)
        armConnectTimeout(activeCall)
    }

    private fun actionsFor(session: VoipMediaSession) = object : VoipCallActions {
        override fun answer() = session.answer()
        override fun reject() = session.reject()
        override fun hangUp() = session.hangUp()
    }

    private fun observe(call: ActiveCall) {
        call.stateJob = scope.launch {
            call.session.state.collect { state ->
                call.handle.onState(state)
                ui.changed()
                when (state) {
                    VoipCallState.InCall -> {
                        call.answered = true
                        call.timeoutJob?.cancel()
                        telecom.setActive(call.handle.id)
                        ui.startCallService()
                        ui.showOngoing(call.handle)
                    }
                    is VoipCallState.Ended -> finish(call)
                    else -> Unit
                }
            }
        }
    }

    private fun armConnectTimeout(call: ActiveCall) {
        call.timeoutJob?.cancel()
        call.timeoutJob = scope.launch {
            delay(VOIP_CONNECT_TIMEOUT_MS)
            if (active !== call || call.answered) return@launch
            call.session.hangUp()
            fallBack(call)
        }
    }

    /** Tears the VoIP attempt down and hands the call to the carrier. */
    private fun fallBack(call: ActiveCall) {
        val carrier = call.onFallbackToCarrier
        finish(call)
        carrier?.invoke()
    }

    private fun finish(call: ActiveCall) {
        if (active !== call) return
        active = null
        call.timeoutJob?.cancel()
        call.stateJob?.cancel()
        telecom.remove(call.handle.id)
        ui.clearNotification()
        ui.stopCallService()
        ui.removed(call.handle.id)
    }
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/telecom/CoreTelecomRegistrar.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real `androidx.core.telecom` registration. `addCall` suspends for the whole
 * call session, so it runs in its own job and the scope it hands back is kept
 * for `setActive` and `disconnect`.
 */
@Singleton
class CoreTelecomRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : VoipTelecom {

    private val callsManager = CallsManager(context)
    private var registered = false

    private var sessionJob: Job? = null
    private var controlScope: CallControlScope? = null
    private var currentId: String? = null

    override fun add(
        handle: VoipCallHandle,
        onSystemAnswer: () -> Unit,
        onSystemDisconnect: () -> Unit,
    ): Boolean = try {
        if (!registered) {
            callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            registered = true
        }
        val attributes = CallAttributesCompat(
            displayName = handle.displayName ?: handle.number.orEmpty(),
            address = Uri.fromParts("tel", handle.number.orEmpty(), null),
            direction = if (handle.telecomState == android.telecom.Call.STATE_RINGING) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        currentId = handle.id
        sessionJob = scope.launch {
            try {
                // addCall suspends for the whole session, so this job stays
                // alive until the call ends or remove() cancels it.
                callsManager.addCall(
                    callAttributes = attributes,
                    onAnswer = { onSystemAnswer() },
                    onDisconnect = { onSystemDisconnect() },
                    onSetActive = { },
                    onSetInactive = { onSystemDisconnect() },
                ) {
                    controlScope = this
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telecom refused the ARK call", e)
            }
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Telecom registration failed", e)
        false
    }

    override fun setActive(id: String) {
        if (currentId != id) return
        val control = controlScope ?: return
        scope.launch { control.setActive() }
    }

    override fun remove(id: String) {
        if (currentId != id) return
        val control = controlScope
        currentId = null
        controlScope = null
        val job = sessionJob
        sessionJob = null
        scope.launch {
            runCatching { control?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
            job?.cancel()
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/telecom/CallControllerVoipCallUi.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallNotifications
import org.jarsi.arkphone.ui.incall.InCallActivity
import org.jarsi.arkphone.voip.VoipForegroundService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes an ARK call through exactly the surfaces a carrier call uses: the
 * same CallController, the same notification (no CallStyle, no full-screen
 * intent — that decision is field-tested) and the same InCallActivity.
 */
@Singleton
class CallControllerVoipCallUi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callController: CallController,
    private val callNotifications: CallNotifications,
) : VoipCallUi {

    override fun added(handle: VoipCallHandle) {
        callNotifications.ensureChannels()
        callController.onCallAdded(handle)
    }

    override fun changed() = callController.onCallChanged()

    override fun removed(id: String) = callController.onCallRemoved(id)

    override fun showIncoming(handle: VoipCallHandle) {
        callController.calls.value.firstOrNull { it.id == handle.id }
            ?.let(callNotifications::showIncomingCall)
    }

    override fun showOngoing(handle: VoipCallHandle) {
        callController.calls.value.firstOrNull { it.id == handle.id }
            ?.let(callNotifications::showOngoingCall)
    }

    override fun clearNotification() = callNotifications.clear()

    override fun openCallScreen() {
        context.startActivity(InCallActivity.intent(context))
    }

    override fun startCallService() = VoipForegroundService.start(context)

    override fun stopCallService() = VoipForegroundService.stop(context)
}
```

Replace `app/src/debug/java/org/jarsi/arkphone/voip/VoipForegroundService.kt` with the same file with two changes: the service type now includes `phoneCall`, and the audio mode is only touched while the service runs.

```kotlin
package org.jarsi.arkphone.voip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import org.jarsi.arkphone.R

/** Keeps the mic and the WebRTC connection alive while an ARK call is active. */
class VoipForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voip_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.voip_notification_title))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Telecom owns routing for a registered call, but the communication
        // mode is what makes the earpiece and the mic behave like a call.
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voip_calls"
        private const val NOTIFICATION_ID = 4001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoipForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoipForegroundService::class.java))
        }
    }
}
```

Add to `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt` (with the matching imports):

```kotlin
    @Provides
    @Singleton
    fun provideVoipTelecom(impl: CoreTelecomRegistrar): VoipTelecom = impl

    @Provides
    @Singleton
    fun provideVoipCallUi(impl: CallControllerVoipCallUi): VoipCallUi = impl

    @Provides
    @Singleton
    fun providePeerConnectionFactoryProvider(
        @ApplicationContext context: Context,
    ): PeerConnectionFactoryProvider = PeerConnectionFactoryProvider(context)

    @Provides
    @Singleton
    fun provideVoipMediaSessionFactory(
        engine: VoipEngine,
        accountClient: ArkAccountClient,
        identityRepository: ArkIdentityRepository,
        provider: PeerConnectionFactoryProvider,
    ): VoipMediaSessionFactory = VoipMediaSessionFactory { peerCode, offerSdp, scope ->
        WebRtcCallSession(
            signaling = EngineSignaling(engine),
            adapterFactory = StreamPeerConnectionAdapterFactory(provider),
            turnFetcher = {
                val identity = identityRepository.identity.first()
                identity?.let {
                    accountClient.turnCredentials("${it.code}.${it.deviceToken}")
                }
            },
            scope = scope,
            peerId = peerCode,
            initialOfferSdp = offerSdp,
        )
    }

    @Provides
    @Singleton
    fun provideVoipCallCoordinator(
        engine: VoipEngine,
        sessionFactory: VoipMediaSessionFactory,
        telecom: VoipTelecom,
        ui: VoipCallUi,
        linkCache: ArkLinkCache,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): VoipCallCoordinator = VoipCallCoordinator(
        reachCheck = { code, timeoutMs -> engine.reach(code, timeoutMs) },
        sessionFactory = sessionFactory,
        telecom = telecom,
        ui = ui,
        nicknameForCode = { code -> linkCache.current.values.firstOrNull { it.code == code }?.nickname },
        numberForCode = { code -> linkCache.current.values.firstOrNull { it.code == code }?.number },
        clock = clock,
        scope = scope,
    )

    @Provides
    @Singleton
    fun provideVoipCallGateway(impl: VoipCallCoordinator): VoipCallGateway = impl
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `VoipCallCoordinatorTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Register ARK calls with Telecom and drive the existing call UI"
```

---

### Task 17: Call-log marker and missed-call notification

**Files:**
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/ArkCallLogWriter.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinator.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/ArkCallRecordTest.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinatorTest.kt`

**Interfaces:**
- Consumes: `VoipCallHandle`, `VoipCallDirection`, `VoipCallState`, `MissedCallNotifier`, `Clock`.
- Produces: `data class ArkCallRecord(number, displayName, type, startedAtMillis, durationSeconds)`, `enum class ArkCallType { INCOMING, OUTGOING, MISSED }`, `fun arkCallRecordOf(handle, direction, endReason, endedAtMillis): ArkCallRecord`, `const val ARK_PHONE_ACCOUNT_ID = "ark-voip"`, `interface ArkCallLog { fun record(record: ArkCallRecord) }`, `class SystemArkCallLog`, `VoipCallCoordinator` gains `callLog: ArkCallLog` and `missedCalls: (ArkCallRecord) -> Unit` parameters.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/ArkCallRecordTest.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkCallRecordTest {

    private object NoActions : VoipCallActions {
        override fun answer() = Unit
        override fun reject() = Unit
        override fun hangUp() = Unit
    }

    private fun handle(direction: VoipCallDirection, connectedAt: Long?) = VoipCallHandle(
        id = "voip-1",
        number = "+358 44 5552841",
        displayName = "Jarsi",
        direction = direction,
        actions = NoActions,
        clock = Clock { connectedAt ?: 0L },
    ).apply { if (connectedAt != null) onState(VoipCallState.InCall) }

    @Test
    fun anAnsweredIncomingCallIsLoggedWithItsDuration() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = 10_000L),
            direction = VoipCallDirection.INCOMING,
            endReason = "peer-hangup",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.INCOMING, record.type)
        assertEquals(10_000L, record.startedAtMillis)
        assertEquals(30L, record.durationSeconds)
        assertEquals("+358 44 5552841", record.number)
        assertEquals("Jarsi", record.displayName)
    }

    @Test
    fun anUnansweredIncomingCallIsAMissedCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = null),
            direction = VoipCallDirection.INCOMING,
            endReason = "no-answer",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.MISSED, record.type)
        assertEquals(40_000L, record.startedAtMillis)
        assertEquals(0L, record.durationSeconds)
    }

    @Test
    fun anIncomingCallTheUserDeclinedIsNotAMissedCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = null),
            direction = VoipCallDirection.INCOMING,
            endReason = "local-reject",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.INCOMING, record.type)
    }

    @Test
    fun anOutgoingCallThatWasNeverAnsweredIsStillAnOutgoingCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.OUTGOING, connectedAt = null),
            direction = VoipCallDirection.OUTGOING,
            endReason = "no-answer",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.OUTGOING, record.type)
        assertEquals(0L, record.durationSeconds)
    }
}
```

Add to `app/src/testDebug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinatorTest.kt` — a fake call log, wired through the coordinator factory, plus two cases:

```kotlin
    private class FakeCallLog : ArkCallLog {
        val records = mutableListOf<ArkCallRecord>()
        override fun record(record: ArkCallRecord) { records += record }
    }
```

Add `private val callLog = FakeCallLog()` and `private val missed = mutableListOf<ArkCallRecord>()` as fields, pass `callLog = callLog, missedCalls = { missed += it }` in the `VoipCallCoordinator(...)` construction inside the `coordinator(...)` helper, and add:

```kotlin
    @Test
    fun anAnsweredCallIsWrittenToTheCallLog() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { }
        advanceUntilIdle()
        session.moveTo(VoipCallState.InCall)
        advanceUntilIdle()
        session.moveTo(VoipCallState.Ended("peer-hangup"))
        advanceUntilIdle()
        assertEquals(ArkCallType.OUTGOING, callLog.records.single().type)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun anUnansweredIncomingCallLeavesAMissedCall() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        advanceUntilIdle()
        session.moveTo(VoipCallState.Ended("no-answer"))
        advanceUntilIdle()
        assertEquals(ArkCallType.MISSED, callLog.records.single().type)
        assertEquals(1, missed.size)
    }

    @Test
    fun aCarrierFallbackIsNotLoggedAsAnArkCall() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = false))
        coordinator.startCall(link) { }
        advanceUntilIdle()
        assertTrue(callLog.records.isEmpty())
    }
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.telecom.*"
```

Expected: compilation failure — `Unresolved reference: arkCallRecordOf`, `ArkCallLog`, `ArkCallType`.

- [ ] **Step 3: Implement**

Create `app/src/debug/java/org/jarsi/arkphone/voip/telecom/ArkCallLogWriter.kt`:

```kotlin
package org.jarsi.arkphone.voip.telecom

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Marks a call-log row as one ARK carried over the internet. */
const val ARK_PHONE_ACCOUNT_ID: String = "ark-voip"

enum class ArkCallType { INCOMING, OUTGOING, MISSED }

data class ArkCallRecord(
    val number: String?,
    val displayName: String?,
    val type: ArkCallType,
    val startedAtMillis: Long,
    val durationSeconds: Long,
)

/**
 * A call that was never answered is missed on the receiving side; the caller's
 * own decline is a deliberate choice, so it is an ordinary incoming row.
 */
fun arkCallRecordOf(
    handle: VoipCallHandle,
    direction: VoipCallDirection,
    endReason: String,
    endedAtMillis: Long,
): ArkCallRecord {
    val connectedAt = handle.connectTimeMillis
    val answered = connectedAt > 0L
    val type = when {
        direction == VoipCallDirection.OUTGOING -> ArkCallType.OUTGOING
        answered -> ArkCallType.INCOMING
        endReason == LOCAL_REJECT -> ArkCallType.INCOMING
        else -> ArkCallType.MISSED
    }
    return ArkCallRecord(
        number = handle.number,
        displayName = handle.displayName,
        type = type,
        startedAtMillis = if (answered) connectedAt else endedAtMillis,
        durationSeconds = if (answered) (endedAtMillis - connectedAt) / 1_000L else 0L,
    )
}

private const val LOCAL_REJECT = "local-reject"

interface ArkCallLog {
    fun record(record: ArkCallRecord)
}

@Singleton
class SystemArkCallLog @Inject constructor(
    @ApplicationContext private val context: Context,
) : ArkCallLog {

    // ARK holds WRITE_CALL_LOG as the default dialer; runCatching covers the
    // window where the role has been taken away.
    @SuppressLint("MissingPermission")
    override fun record(record: ArkCallRecord) {
        val values = ContentValues().apply {
            put(CallLog.Calls.NUMBER, record.number.orEmpty())
            put(CallLog.Calls.CACHED_NAME, record.displayName)
            put(
                CallLog.Calls.TYPE,
                when (record.type) {
                    ArkCallType.INCOMING -> CallLog.Calls.INCOMING_TYPE
                    ArkCallType.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
                    ArkCallType.MISSED -> CallLog.Calls.MISSED_TYPE
                },
            )
            put(CallLog.Calls.DATE, record.startedAtMillis)
            put(CallLog.Calls.DURATION, record.durationSeconds)
            put(CallLog.Calls.NEW, if (record.type == ArkCallType.MISSED) 1 else 0)
            put(CallLog.Calls.IS_READ, if (record.type == ArkCallType.MISSED) 0 else 1)
            // The marker that says this row was an ARK internet call.
            put(CallLog.Calls.PHONE_ACCOUNT_ID, ARK_PHONE_ACCOUNT_ID)
        }
        runCatching { context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values) }
            .onFailure { Log.w(TAG, "ARK call not written to the call log", it) }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
```

In `app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinator.kt`:

- add two constructor parameters, after `numberForCode`:

```kotlin
    private val callLog: ArkCallLog,
    private val missedCalls: (ArkCallRecord) -> Unit,
```

- give `ActiveCall` a direction so the record knows which way the call went; add `val direction: VoipCallDirection,` as its second constructor property and pass `VoipCallDirection.OUTGOING` / `VoipCallDirection.INCOMING` at the two creation sites.

- replace `finish` with:

```kotlin
    private fun finish(call: ActiveCall) {
        if (active !== call) return
        active = null
        call.timeoutJob?.cancel()
        call.stateJob?.cancel()
        telecom.remove(call.handle.id)
        ui.clearNotification()
        ui.stopCallService()
        ui.removed(call.handle.id)
        val ended = call.handle.state as? VoipCallState.Ended ?: return
        // A carrier fallback is one call from the user's point of view; only a
        // VoIP attempt that actually reached the peer leaves a row.
        val record = arkCallRecordOf(
            handle = call.handle,
            direction = call.direction,
            endReason = ended.reason,
            endedAtMillis = clock.nowMillis(),
        )
        callLog.record(record)
        if (record.type == ArkCallType.MISSED) missedCalls(record)
    }
```

`fallBack` calls `finish` while the session is still in `Connecting`, so `call.handle.state` is not `Ended` there and nothing is logged — which is what `aCarrierFallbackIsNotLoggedAsAnArkCall` asserts.

In `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`, add the binding and extend the coordinator provider:

```kotlin
    @Provides
    @Singleton
    fun provideArkCallLog(impl: SystemArkCallLog): ArkCallLog = impl
```

and inside `provideVoipCallCoordinator`, add `callLog: ArkCallLog` and `missedCallNotifier: MissedCallNotifier` parameters plus:

```kotlin
        callLog = callLog,
        missedCalls = { record ->
            missedCallNotifier.onMissedCallsChanged(count = 1, number = record.number)
        },
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `ArkCallRecordTest` and `VoipCallCoordinatorTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Write ARK calls to the call log and notify missed ones"
```

---

## Stage B4: Routing — the place-call branch and the fallback matrix

### Task 18: The call router

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/telecom/CallRouter.kt`
- Test: `app/src/test/java/org/jarsi/arkphone/telecom/CallRouterTest.kt`

**Interfaces:**
- Consumes: `PhoneCaller.placeCall(number: String): Boolean`, `PhoneCaller.placeVoicemailCall(): Boolean`, `SettingsCache.current`, `ArkLinkCache.linkFor(number)`, `VoipCallGateway.startCall(link, onFallbackToCarrier)`, `FakeVoipCallGateway` (Task 5).
- Produces: `@Singleton class CallRouter @Inject constructor(phoneCaller, settingsCache, linkCache, voipCallGateway: Optional<VoipCallGateway>)` with `fun placeCall(number: String): Boolean` and `fun placeVoicemailCall(): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/jarsi/arkphone/telecom/CallRouterTest.kt`:

```kotlin
package org.jarsi.arkphone.telecom

import android.Manifest
import android.net.Uri
import android.telecom.PhoneAccountHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.testing.FakeSimAccountRepository
import org.jarsi.arkphone.testing.FakeVoipCallGateway
import org.jarsi.arkphone.voip.ArkLinkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Optional

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallRouterTest {

    private val placed = mutableListOf<Pair<Uri, PhoneAccountHandle?>>()
    private val links = FakeArkLinkRepository()
    private val gateway = FakeVoipCallGateway()

    private suspend fun router(
        settings: Settings = Settings(),
        withGateway: Boolean = true,
        scope: CoroutineScope,
    ): CallRouter {
        val settingsCache = SettingsCache(FakeSettingsRepository(settings), scope)
        settingsCache.await()
        val linkCache = ArkLinkCache(links, scope)
        linkCache.await()
        val phoneCaller = PhoneCaller(
            permissionChecker = FakePermissionChecker().apply {
                grant(Manifest.permission.CALL_PHONE)
            },
            simAccountRepository = FakeSimAccountRepository(),
            settingsCache = settingsCache,
            callPlacer = { uri, accountHandle -> placed += uri to accountHandle },
        )
        return CallRouter(
            phoneCaller = phoneCaller,
            settingsCache = settingsCache,
            linkCache = linkCache,
            voipCallGateway = if (withGateway) Optional.of(gateway) else Optional.empty(),
        )
    }

    private suspend fun givenLink() {
        links.link("+358 44 5552841", "ARK-BBBB-BBBB", "Jarsi", "pk", 1_000L)
    }

    @Test
    fun anUnlinkedNumberGoesStraightToTheCarrier() = runTest {
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("+358 40 1112223"))
        assertEquals("+358 40 1112223", placed.single().first.schemeSpecificPart)
        assertTrue(gateway.started.isEmpty())
    }

    @Test
    fun aLinkedNumberIsHandedToTheVoipGateway() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("ARK-BBBB-BBBB", gateway.started.single().code)
        assertTrue(placed.isEmpty())
    }

    @Test
    fun theGatewaysFallbackPlacesTheCarrierCall() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        router.placeCall("044 555 2841")
        gateway.lastFallback!!()
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun aRefusedVoipAttemptBecomesACarrierCallImmediately() = runTest {
        givenLink()
        gateway.accept = false
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun aThrowingGatewayStillPlacesAPhoneCall() = runTest {
        givenLink()
        gateway.throwOnStart = true
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun theMasterSwitchKeepsLinksButStopsRouting() = runTest {
        givenLink()
        val router = router(
            settings = Settings(arkInternetCallsEnabled = false),
            scope = backgroundScope,
        )
        assertTrue(router.placeCall("044 555 2841"))
        assertTrue(gateway.started.isEmpty())
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun aBuildWithoutTheEngineRoutesEverythingToTheCarrier() = runTest {
        givenLink()
        val router = router(withGateway = false, scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun emergencyAndUssdNumbersAreUntouched() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("112"))
        assertTrue(router.placeCall("*#06#"))
        assertTrue(gateway.started.isEmpty())
        assertEquals(listOf("112", "*#06#"), placed.map { it.first.schemeSpecificPart })
    }

    @Test
    fun aBlankNumberIsStillNotCalled() = runTest {
        val router = router(scope = backgroundScope)
        assertFalse(router.placeCall("  "))
        assertTrue(placed.isEmpty())
    }

    @Test
    fun voicemailNeverGoesOverTheInternet() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        assertTrue(router.placeVoicemailCall())
        assertEquals("voicemail", placed.single().first.scheme)
        assertTrue(gateway.started.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.telecom.CallRouterTest"
```

Expected: compilation failure — `Unresolved reference: CallRouter`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/org/jarsi/arkphone/telecom/CallRouter.kt`:

```kotlin
package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.voip.ArkLinkCache
import org.jarsi.arkphone.voip.VoipCallGateway
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one branch in the outgoing-call path. Every rejection lands on the same
 * answer — place the carrier call — because VoIP must never prevent a phone
 * call. Emergency numbers, USSD codes and every unlinked number take the
 * carrier path on the first check, with no network work and no added latency:
 * the link lookup is an in-memory map read.
 */
@Singleton
class CallRouter @Inject constructor(
    private val phoneCaller: PhoneCaller,
    private val settingsCache: SettingsCache,
    private val linkCache: ArkLinkCache,
    private val voipCallGateway: Optional<VoipCallGateway>,
) {
    /** Returns false only when the call could not be placed at all. */
    fun placeCall(number: String): Boolean {
        if (number.isBlank()) return false
        val gateway = voipCallGateway.orElse(null) ?: return phoneCaller.placeCall(number)
        if (!settingsCache.current.arkInternetCallsEnabled) return phoneCaller.placeCall(number)
        val link = linkCache.linkFor(number) ?: return phoneCaller.placeCall(number)
        // A bug anywhere in the engine must still leave the user with a call.
        val started = runCatching {
            gateway.startCall(link) { phoneCaller.placeCall(number) }
        }.getOrDefault(false)
        if (started) return true
        return phoneCaller.placeCall(number)
    }

    /** Voicemail is always the operator's; there is no internet equivalent. */
    fun placeVoicemailCall(): Boolean = phoneCaller.placeVoicemailCall()
}
```

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; `CallRouterTest` green.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone/telecom/CallRouter.kt app/src/test/java/org/jarsi/arkphone/telecom/CallRouterTest.kt
git commit -m "Add the call router with the VoIP or carrier branch"
```

---

### Task 19: Route every call site and start the engine with the app

**Files:**
- Create: `app/src/main/java/org/jarsi/arkphone/voip/VoipStartup.kt`
- Create: `app/src/debug/java/org/jarsi/arkphone/voip/ArkVoipStartup.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ArkPhoneApp.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/MainActivity.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardActivity.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/detail/CallDetailActivity.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/ui/conversation/ConversationActivity.kt`
- Modify: `app/src/main/java/org/jarsi/arkphone/telecom/CallActionReceiver.kt`
- Modify: `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt`
- Test: `app/src/testDebug/java/org/jarsi/arkphone/voip/ArkVoipStartupTest.kt`

**Interfaces:**
- Consumes: `CallRouter`, `VoipEngine`, `IncomingArkCall`, `VoipCallCoordinator.onIncoming`, `ArkFcmRegistration.refresh`.
- Produces: `interface VoipStartup { fun onAppStart() }` (main tree, optional binding), `class ArkVoipStartup(engine, onIncoming, fcmRefresh, scope) : VoipStartup`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/org/jarsi/arkphone/voip/ArkVoipStartupTest.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarsi.arkphone.data.ArkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArkVoipStartupTest {

    private class StubHandle : WebSocketHandle {
        override fun send(text: String): Boolean = true
        override fun close() = Unit
    }

    private class StartupConnector : WebSocketConnector {
        val handles = mutableListOf<StubHandle>()
        var lastOnOpen: (() -> Unit)? = null
        var lastOnText: ((String) -> Unit)? = null
        override fun connect(
            url: String,
            bearer: String,
            onOpen: () -> Unit,
            onText: (String) -> Unit,
            onClosed: (Int, String) -> Unit,
        ): WebSocketHandle {
            lastOnOpen = onOpen
            lastOnText = onText
            return StubHandle().also { handles += it }
        }
    }

    @Test
    fun startupRefreshesTheFcmTokenAndOpensTheInbox() = runTest {
        val connector = StartupConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        var refreshed = false
        ArkVoipStartup(engine, { }, { refreshed = true }, backgroundScope).onAppStart()
        runCurrent()
        assertTrue(refreshed)
        assertEquals(1, connector.handles.size)
    }

    @Test
    fun aReconciledIncomingCallReachesTheCoordinator() = runTest {
        val connector = StartupConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val received = mutableListOf<IncomingArkCall>()
        ArkVoipStartup(engine, { received += it }, { }, backgroundScope).onAppStart()
        runCurrent()
        connector.lastOnOpen!!()
        runCurrent()
        connector.lastOnText!!(
            SignalingJson.encode(
                SignalingMessage(
                    type = SignalingTypes.CALL_OFFER,
                    from = "ARK-BBBB-BBBB",
                    payload = buildJsonObject { put("sdp", "v=0") },
                ),
            ),
        )
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        advanceUntilIdle()
        assertEquals(listOf(IncomingArkCall("ARK-BBBB-BBBB", "v=0")), received)
    }

    @Test
    fun startupIsIdempotent() = runTest {
        val connector = StartupConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val startup = ArkVoipStartup(engine, { }, { }, backgroundScope)
        startup.onAppStart()
        startup.onAppStart()
        runCurrent()
        assertEquals(1, connector.handles.size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```
.\gradlew.bat :app:testDebugUnitTest --tests "org.jarsi.arkphone.voip.ArkVoipStartupTest"
```

Expected: compilation failure — `Unresolved reference: ArkVoipStartup`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/org/jarsi/arkphone/voip/VoipStartup.kt`:

```kotlin
package org.jarsi.arkphone.voip

/**
 * Called once per process. Bound only in builds that carry the VoIP engine, so
 * a release build starts nothing and opens no socket.
 */
interface VoipStartup {
    fun onAppStart()
}
```

In `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt` add the import `org.jarsi.arkphone.voip.VoipStartup` and, next to the other optional bindings:

```kotlin
    @BindsOptionalOf
    abstract fun optionalVoipStartup(): VoipStartup
```

In `app/src/main/java/org/jarsi/arkphone/ArkPhoneApp.kt`:

- add the imports:

```kotlin
import org.jarsi.arkphone.voip.VoipStartup
import java.util.Optional
```

- add the injection point next to the others:

```kotlin
    @Inject lateinit var voipStartup: Optional<VoipStartup>
```

- and add as the last statement of `onCreate`:

```kotlin
        // Empty in release: no engine, no socket, no push.
        voipStartup.ifPresent(VoipStartup::onAppStart)
```

Create `app/src/debug/java/org/jarsi/arkphone/voip/ArkVoipStartup.kt`:

```kotlin
package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Brings the VoIP engine up with the process: the FCM token is refreshed so
 * the worker can wake this device, the inbox socket opens, and everything the
 * flush reconciles into a call is handed to the call coordinator.
 */
class ArkVoipStartup(
    private val engine: VoipEngine,
    private val onIncoming: (IncomingArkCall) -> Unit,
    private val fcmRefresh: () -> Unit,
    private val scope: CoroutineScope,
) : VoipStartup {

    private var started: Job? = null

    override fun onAppStart() {
        if (started != null) return
        fcmRefresh()
        scope.launch { engine.incomingCalls.collect(onIncoming) }
        started = scope.launch { engine.connect() }
    }
}
```

Add to `app/src/debug/java/org/jarsi/arkphone/voip/di/VoipModule.kt` (with the matching imports):

```kotlin
    @Provides
    @Singleton
    fun provideVoipStartup(
        engine: VoipEngine,
        coordinator: VoipCallCoordinator,
        fcmRegistration: ArkFcmRegistration,
        @ApplicationScope scope: CoroutineScope,
    ): VoipStartup = ArkVoipStartup(
        engine = engine,
        onIncoming = coordinator::onIncoming,
        fcmRefresh = fcmRegistration::refresh,
        scope = scope,
    )
```

Now switch every call site from `PhoneCaller` to `CallRouter`. In each of the five files below, replace the `PhoneCaller` import with `org.jarsi.arkphone.telecom.CallRouter`, rename the injected field, and change the call.

`app/src/main/java/org/jarsi/arkphone/MainActivity.kt`:

```kotlin
    @Inject lateinit var callRouter: CallRouter
```

```kotlin
                        onCall = { number -> callRouter.placeCall(number) },
                        onVoicemail = { callRouter.placeVoicemailCall() },
```

`app/src/main/java/org/jarsi/arkphone/ui/contactcard/ContactCardActivity.kt`:

```kotlin
    @Inject lateinit var callRouter: CallRouter
```

```kotlin
                    onCall = { callRouter.placeCall(it) },
```

`app/src/main/java/org/jarsi/arkphone/ui/detail/CallDetailActivity.kt`:

```kotlin
    @Inject lateinit var callRouter: CallRouter
```

```kotlin
                    onCall = { callRouter.placeCall(it) },
```

`app/src/main/java/org/jarsi/arkphone/ui/conversation/ConversationActivity.kt`:

```kotlin
    @Inject lateinit var callRouter: CallRouter
```

```kotlin
                    onCall = { callRouter.placeCall(it) },
```

`app/src/main/java/org/jarsi/arkphone/telecom/CallActionReceiver.kt`:

```kotlin
    @Inject lateinit var callRouter: CallRouter
```

```kotlin
                intent.getStringExtra(MissedCallNotifier.EXTRA_NUMBER)?.let(callRouter::placeCall)
```

`PhoneCaller` itself is unchanged and stays the carrier path `CallRouter` delegates to; `PhoneCallerTest` keeps passing untouched.

- [ ] **Step 4: Run tests**

```
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL; the whole suite green, including `ArkVoipStartupTest`, `CallRouterTest` and `PhoneCallerTest`.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/jarsi/arkphone app/src/debug/java/org/jarsi/arkphone/voip app/src/testDebug/java/org/jarsi/arkphone/voip
git commit -m "Route every call site through the call router and start the engine with the app"
```

---

## Release-safety check before field validation

After Task 19, confirm the release build is untouched. This is not a task with its own commit — it is the gate before the field protocol in the spec's Testing section.

- [ ] Build the release APK and confirm it still builds and installs:
      `.\gradlew.bat :app:assembleRelease`
- [ ] Confirm the release manifest gained nothing. The merged manifest is at
      `app/build/intermediates/merged_manifests/release/processReleaseMainManifest/AndroidManifest.xml`;
      it must contain **none** of `INTERNET`, `RECORD_AUDIO`, `MANAGE_OWN_CALLS`,
      `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_PHONE_CALL`, and no
      `JetpackConnectionService`, `ArkMessagingService` or `VoipForegroundService`.
- [ ] Confirm the release APK carries no VoIP dependency: `androidx.core.telecom`,
      `com.google.firebase`, `io.getstream` and `okhttp3` classes must all be absent.
- [ ] Confirm the ARK settings row is invisible in a release build (the optional
      gateway is empty, so `ArkCallsUiState.available` is false and the contact
      card shows no ARK row).

## Spec coverage map

| Spec / roadmap requirement | Task |
|---|---|
| B1 registration flow (`POST /register`, persist token first) | 4, 5, 11 |
| B1 settings screen: own code, share | 6 |
| B1 contact linking: enter/paste code → confirm nickname → local table | 1, 2, 7 |
| Device keypair, EC P-256 SPKI base64 under the 200-char cap | 10 |
| B2 engine rebase onto ARK codes and per-device bearer | 9, 11 |
| B2 remove the test screen, launcher entry and shared token | 8 |
| FCM data-only wake, payload treated as a hint only | 12, 13 |
| Flush drained and reconciled before ringing | 12 |
| `superseded` close is expected and never reconnects | 9 |
| `send()` return value honoured, reconnect after a network change | 9 |
| `ping`/`pong` bare-string keepalive tolerated | 9 |
| `reach-reply` online:true terminal, order-independent, ignored when unsolicited | 9 |
| B3 `VoipCallHandle` implementing `CallHandle` | 14 |
| B3 `androidx.core.telecom` self-managed registration | 16 |
| B3 one in-call UI (same `CallController`, notification, `InCallActivity`) | 14, 16 |
| B3 15 s connect timeout | 16 |
| B3 call-log marker | 17 |
| B3 missed-call notification | 17 |
| B4 place-call branch | 18, 19 |
| B4 4 s reach pre-check | 16, 18 |
| B4 fallback matrix (unreachable, refused, timeout, decline, no answer) | 16, 17, 18 |
| B4 "ARK internet calls" master switch | 3, 6, 18 |
| Success criterion 6: release gains no permission, dependency or feature | 5, 8, 12–14, 19, release-safety check |
| `google-services.json` absent must not break the build | 13 |

## Resolved ambiguities

- **The spec says "CallStyle notifications" for the VoIP path, but `CallNotifications` deliberately avoids CallStyle and full-screen intents.** The field-tested decision wins: ARK calls ring through the existing non-CallStyle notification and `InCallActivity`, exactly like carrier calls. Foreground execution priority therefore comes from the `phoneCall`-type foreground service rather than from a CallStyle notification.
- **`registerAppWithTelecom` takes one argument in 1.0.1** (a second `backwardsCompatSdkLevel` parameter only exists on the 1.1.0 alphas), and `CallAttributesCompat`'s capability parameter is `callCapabilities: Int`, not a `CallCapability` object as the guide page's sample implies. Both were read from the published 1.0.1 sources.
- **The `hello` / `presence-query` verbs are dropped rather than kept**: their `online: false` contradicts Phase 1 buffering, and the spike's 10 s presence loop is also the source of the Phase 0 test hang.
- **Mid-call drop:** the spec's "call-ended screen with a one-tap Call via mobile" is satisfied by the existing ended-call screen plus the call-log entry; the coordinator never redials into a live conversation, and no automatic fallback fires once `InCall` has been reached (Task 16, `aConnectedCallIsNotTornDownByTheTimeout`).
- **`CallInfo` gains `viaArkCall`** rather than a free-form label, so the "ARK call" text lives in resources and is translated.
