package org.jarsi.arkphone.data.model

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, OTHER }

/** Which app carried the call, derived from the log row's phone account. */
enum class CallSource { PHONE, WHATSAPP, OTHER }

data class CallLogEntry(
    val id: Long,
    val number: String,
    val displayName: String?,
    val type: CallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val source: CallSource = CallSource.PHONE,
    /** The WhatsApp variant that carried the call, for callback routing. */
    val whatsAppPackage: String? = null,
    /** Phone account the call went through; null for app calls and old rows. */
    val simAccountId: String? = null,
    /** True when the call was carried over Wi-Fi (VoWiFi). */
    val wasWifiCall: Boolean = false,
    /** True when the call went over the internet as an ARK call. */
    val viaArkCall: Boolean = false,
)

/** The PHONE_ACCOUNT_ID marker ARK stamps on its own internet-call rows. */
const val ARK_PHONE_ACCOUNT_ID: String = "ark-voip"
