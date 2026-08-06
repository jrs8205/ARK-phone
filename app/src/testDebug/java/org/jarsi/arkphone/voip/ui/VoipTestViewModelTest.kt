package org.jarsi.arkphone.voip.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jarsi.arkphone.voip.IceServerConfig
import org.jarsi.arkphone.voip.SignalingClient
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.WebRtcCallSession
import org.jarsi.arkphone.voip.WebSocketConnector
import org.jarsi.arkphone.voip.WebSocketHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoipTestViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class NoopConnector : WebSocketConnector {
        override fun connect(
            url: String,
            onText: (String) -> Unit,
            onClosed: () -> Unit,
        ) = object : WebSocketHandle {
            override fun send(text: String) = true
            override fun close() {}
        }
    }

    private fun factory() = object : VoipSessionFactory {
        override fun create(
            deviceId: String,
            peerId: String,
            scope: CoroutineScope,
        ): VoipSessionHandles {
            val signaling = SignalingClient(NoopConnector(), "https://w", deviceId, peerId, scope)
            val session = WebRtcCallSession(
                signaling = signaling,
                adapterFactory = { _, _ -> error("not used in this test") },
                turnFetcher = { listOf(IceServerConfig(urls = listOf("stun:s"))) },
                scope = scope,
                peerId = peerId,
            )
            return VoipSessionHandles(signaling, session)
        }
    }

    @Test
    fun `starts with no device picked`() = runTest(dispatcher.scheduler) {
        val viewModel = VoipTestViewModel(factory())
        assertNull(viewModel.uiState.value.deviceId)
    }

    // pickDevice starts the signaling client's endless presence-query loop in
    // viewModelScope, which shares this test's virtual clock; without the
    // finally-cancel the scheduler never idles and runTest never returns
    // (production tears this down via onCleared instead).
    @Test
    fun `picking a device sets the peer to the other phone and connects`() = runTest(dispatcher.scheduler) {
        val viewModel = VoipTestViewModel(factory())
        try {
            viewModel.pickDevice("phone-8a")
            runCurrent()
            assertEquals("phone-8a", viewModel.uiState.value.deviceId)
            assertEquals("phone-10pro", viewModel.uiState.value.peerId)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `call state flows into ui state`() = runTest(dispatcher.scheduler) {
        val viewModel = VoipTestViewModel(factory())
        try {
            viewModel.pickDevice("phone-10pro")
            runCurrent()
            assertEquals(VoipCallState.Idle, viewModel.uiState.value.callState)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
