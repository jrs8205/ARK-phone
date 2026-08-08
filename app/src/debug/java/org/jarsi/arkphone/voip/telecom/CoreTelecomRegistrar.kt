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
