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
