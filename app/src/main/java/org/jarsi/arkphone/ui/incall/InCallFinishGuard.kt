package org.jarsi.arkphone.ui.incall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

const val STALE_LAUNCH_GRACE_MILLIS = 2_000L
const val ENDED_GRACE_MILLIS = 1_500L

/**
 * Finishes the in-call screen when it has nothing to show: shortly after a
 * previously seen call ends (so "call ended" stays readable for a moment),
 * or after [graceMillis] if the screen was launched without any call ever
 * appearing (stale notification race).
 */
@Composable
fun InCallFinishGuard(
    hasCall: Boolean,
    graceMillis: Long = STALE_LAUNCH_GRACE_MILLIS,
    endedGraceMillis: Long = ENDED_GRACE_MILLIS,
    onFinish: () -> Unit,
) {
    var sawCall by remember { mutableStateOf(false) }
    val currentOnFinish by rememberUpdatedState(onFinish)
    LaunchedEffect(hasCall) {
        if (hasCall) {
            sawCall = true
        } else if (sawCall) {
            delay(endedGraceMillis)
            currentOnFinish()
        }
    }
    LaunchedEffect(Unit) {
        delay(graceMillis)
        if (!sawCall) {
            currentOnFinish()
        }
    }
}
