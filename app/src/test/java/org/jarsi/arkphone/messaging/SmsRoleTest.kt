package org.jarsi.arkphone.messaging

import android.app.Application
import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SmsRoleTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a held role is reported`() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_SMS)
        assertTrue(AndroidSmsRole(context).isHeld())
    }

    @Test
    fun `a missing role is reported and requestable`() {
        val role = AndroidSmsRole(context)
        assertFalse(role.isHeld())
        assertNotNull(role.requestIntent())
    }
}
