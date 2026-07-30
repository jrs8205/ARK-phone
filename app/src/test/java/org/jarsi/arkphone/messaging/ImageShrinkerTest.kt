package org.jarsi.arkphone.messaging

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageShrinkerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val shrinker = ImageShrinker(context)

    @Test
    fun `a large bitmap shrinks under the budget and stays decodable`() = runTest {
        val source = File(context.cacheDir, "large.jpg")
        Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF3366AA.toInt())
            source.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }

        val shrunk = shrinker.shrink(Uri.fromFile(source), maxBytes = 100_000)

        assertNotNull(shrunk)
        assertTrue("size ${shrunk!!.size}", shrunk.size <= 100_000)
        assertNotNull(BitmapFactory.decodeByteArray(shrunk, 0, shrunk.size))
    }

    @Test
    fun `corrupt input yields null`() = runTest {
        val source = File(context.cacheDir, "corrupt.jpg")
        source.writeBytes("this is not an image".toByteArray())
        assertNull(shrinker.shrink(Uri.fromFile(source), maxBytes = 100_000))
    }
}
