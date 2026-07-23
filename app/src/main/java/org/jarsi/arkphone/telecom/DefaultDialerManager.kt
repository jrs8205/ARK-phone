package org.jarsi.arkphone.telecom

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

class DefaultDialerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
) {
    fun isDefaultDialer(): Boolean {
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return false
        return telecomManager.defaultDialerPackage == context.packageName
    }

    fun requestIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }

    fun corePermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    fun hasCorePermissions(): Boolean = corePermissions().all(permissionChecker::has)
}
