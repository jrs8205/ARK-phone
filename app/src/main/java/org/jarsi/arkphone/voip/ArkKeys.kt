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
