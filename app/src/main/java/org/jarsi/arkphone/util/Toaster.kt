package org.jarsi.arkphone.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Short user notice from a place that has no UI surface of its own. */
fun interface Toaster {
    fun show(@StringRes messageRes: Int)
}

class AndroidToaster @Inject constructor(
    @ApplicationContext private val context: Context,
) : Toaster {
    override fun show(@StringRes messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
    }
}
