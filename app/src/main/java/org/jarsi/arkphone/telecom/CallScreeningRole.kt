package org.jarsi.arkphone.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The caller ID & spam role: Android hands call screening to its holder
 * (on this user's Pixel that was Google Phone), so our blocking rules only
 * run once ARK-phone holds it. Below Android 10 the role does not exist and
 * the default dialer screens calls directly.
 */
interface CallScreeningRole {
    fun isHeld(): Boolean

    fun requestIntent(): Intent?
}

class AndroidCallScreeningRole @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallScreeningRole {

    override fun isHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    override fun requestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }
}
