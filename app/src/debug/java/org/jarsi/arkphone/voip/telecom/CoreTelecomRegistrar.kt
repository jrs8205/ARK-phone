package org.jarsi.arkphone.voip.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
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
    private var endpointJob: Job? = null
    private var controlScope: CallControlScope? = null
    private var currentId: String? = null

    /** An answer that arrived before addCall handed its scope back. */
    private var pendingAnswer = false

    /**
     * A route choice that could not be applied yet — tapped before addCall
     * handed its scope back, or before Telecom advertised the endpoint. The
     * UI shows the choice optimistically, so dropping it left the audio on
     * the earpiece under a speaker icon.
     */
    private var pendingSpeaker: Boolean? = null

    /** The endpoints Telecom last advertised for the live call. */
    private var endpoints: List<CallEndpointCompat> = emptyList()

    override fun add(
        handle: VoipCallHandle,
        onSystemAnswer: () -> Unit,
        onSystemDisconnect: () -> Unit,
        onFailed: () -> Unit,
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
            // Phase 1 has no hold, so the capability must not be advertised:
            // the system turned its setInactive requests (an arriving carrier
            // call, a hold request) into a dropped internet call.
            callCapabilities = 0,
        )
        currentId = handle.id
        // remove() clears the pending stash only when it still owns the
        // call; a mismatch on a torn-down path can leave a previous call's
        // answer or route choice behind, and replaying either into this
        // call would answer it unasked or flip it onto the speaker.
        pendingAnswer = false
        pendingSpeaker = null
        endpointJob?.cancel()
        endpointJob = null
        endpoints = emptyList()
        Log.i(TAG, "ARK telecom add id=${handle.id}")
        sessionJob = scope.launch {
            try {
                // addCall suspends for the whole session, so this job stays
                // alive until the call ends or remove() cancels it.
                Log.i(TAG, "ARK addCall begins id=${handle.id}")
                callsManager.addCall(
                    callAttributes = attributes,
                    onAnswer = { onSystemAnswer() },
                    onDisconnect = { onSystemDisconnect() },
                    onSetActive = { },
                    onSetInactive = { },
                ) {
                    controlScope = this
                    endpointJob = scope.launch {
                        availableEndpoints.collect {
                            endpoints = it
                            // The endpoint the user already asked for may
                            // only now exist; apply the remembered choice.
                            replayPendingSpeaker(handle.id)
                        }
                    }
                    if (pendingAnswer) {
                        // The user answered faster than Telecom set up: the
                        // answer must not be dropped on the floor.
                        pendingAnswer = false
                        answered(handle.id)
                    }
                    replayPendingSpeaker(handle.id)
                    Log.i(TAG, "ARK addCall session open id=${handle.id}")
                }
                Log.i(TAG, "ARK addCall ended id=${handle.id}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // remove() cancelling the session job is the normal teardown,
                // not a refusal.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Telecom refused the ARK call", e)
                // add() already returned true; without this the coordinator
                // would keep ringing a call the platform never accepted.
                if (currentId == handle.id) onFailed()
            }
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Telecom registration failed", e)
        false
    }

    override fun answered(id: String) {
        if (currentId != id) return
        val control = controlScope
        if (control == null) {
            pendingAnswer = true
            return
        }
        scope.launch {
            runCatching { control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
                .onSuccess { result -> Log.i(TAG, "ARK telecom answer result=$result") }
                .onFailure { e -> Log.w(TAG, "ARK telecom answer failed", e) }
        }
    }

    override fun setActive(id: String) {
        if (currentId != id) return
        val control = controlScope ?: return
        scope.launch { control.setActive() }
    }

    override fun requestSpeaker(id: String, speakerOn: Boolean) {
        if (currentId != id) return
        val control = controlScope
        val target = endpointFor(speakerOn)
        if (control == null || target == null) {
            pendingSpeaker = speakerOn
            return
        }
        pendingSpeaker = null
        scope.launch { runCatching { control.requestEndpointChange(target) } }
    }

    private fun endpointFor(speakerOn: Boolean): CallEndpointCompat? {
        val wanted = if (speakerOn) {
            CallEndpointCompat.TYPE_SPEAKER
        } else {
            CallEndpointCompat.TYPE_EARPIECE
        }
        return endpoints.firstOrNull { it.type == wanted }
    }

    private fun replayPendingSpeaker(id: String) {
        val speakerOn = pendingSpeaker ?: return
        requestSpeaker(id, speakerOn)
    }

    override fun remove(id: String, onReleased: () -> Unit) {
        if (currentId != id) {
            onReleased()
            return
        }
        val control = controlScope
        currentId = null
        controlScope = null
        pendingAnswer = false
        pendingSpeaker = null
        endpointJob?.cancel()
        endpointJob = null
        endpoints = emptyList()
        val job = sessionJob
        sessionJob = null
        scope.launch {
            runCatching { control?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
            job?.cancel()
            onReleased()
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
