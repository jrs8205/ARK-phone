package org.jarsi.arkphone.data.model

enum class AnnounceMode { OFF, WITH_RINGTONE, VOICE_ONLY }

data class Settings(
    val announceMode: AnnounceMode = AnnounceMode.OFF,
    val announceIntervalSeconds: Int = DEFAULT_ANNOUNCE_INTERVAL_SECONDS,
    val announceWhatsApp: Boolean = false,
    val blockHiddenNumbers: Boolean = false,
    val blockUnknownCallers: Boolean = false,
    val blockedPrefixes: Set<String> = emptySet(),
    val allowRepeatCallers: Boolean = true,
) {
    companion object {
        const val MIN_ANNOUNCE_INTERVAL_SECONDS = 4
        const val MAX_ANNOUNCE_INTERVAL_SECONDS = 10
        const val DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 6
    }
}
