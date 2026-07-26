package org.jarsi.arkphone.data.model

enum class AnnounceMode { OFF, WITH_RINGTONE, VOICE_ONLY }

data class Settings(
    val announceMode: AnnounceMode = AnnounceMode.OFF,
    val announceIntervalSeconds: Int = DEFAULT_ANNOUNCE_INTERVAL_SECONDS,
    val announceWhatsApp: Boolean = false,
    val blockAllCallers: Boolean = false,
    val blockHiddenNumbers: Boolean = false,
    val blockUnknownCallers: Boolean = false,
    val blockedPrefixes: Set<String> = emptySet(),
    val allowRepeatCallers: Boolean = true,
    val repeatCallerWindowMinutes: Int = DEFAULT_REPEAT_WINDOW_MINUTES,
    val allowedNumbers: Set<String> = emptySet(),
    val alwaysAllowFavorites: Boolean = true,
    val blockingScheduleEnabled: Boolean = false,
    val blockingScheduleStartMinutes: Int = DEFAULT_SCHEDULE_START_MINUTES,
    val blockingScheduleEndMinutes: Int = DEFAULT_SCHEDULE_END_MINUTES,
    /** Phone account outgoing calls should use; null means the system default. */
    val callSimAccountId: String? = null,
    /** Phone account the blocking rules are limited to; null means every SIM. */
    val blockingSimAccountId: String? = null,
) {
    companion object {
        const val MIN_ANNOUNCE_INTERVAL_SECONDS = 4
        const val MAX_ANNOUNCE_INTERVAL_SECONDS = 10
        const val DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 6

        /** Blocking schedule defaults: rules active overnight 21:00–07:00. */
        const val DEFAULT_SCHEDULE_START_MINUTES = 21 * 60
        const val DEFAULT_SCHEDULE_END_MINUTES = 7 * 60

        const val MIN_REPEAT_WINDOW_MINUTES = 1
        const val MAX_REPEAT_WINDOW_MINUTES = 60
        const val DEFAULT_REPEAT_WINDOW_MINUTES = 15
    }
}
