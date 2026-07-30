package org.jarsi.arkphone.messaging

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The default-SMS-app role. Only its holder receives messages and may
 *  write the telephony provider. */
interface SmsRole {
    fun isHeld(): Boolean

    fun requestIntent(): Intent?
}

class AndroidSmsRole @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsRole {

    override fun isHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
    }

    override fun requestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }
}
