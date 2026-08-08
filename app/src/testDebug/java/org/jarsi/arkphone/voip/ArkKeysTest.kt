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
