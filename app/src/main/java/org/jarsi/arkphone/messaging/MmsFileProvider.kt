package org.jarsi.arkphone.messaging

import androidx.core.content.FileProvider

/** Shares the cacheDir/mms download and send files with the platform
 *  mms service, which streams the PDUs through this authority. */
class MmsFileProvider : FileProvider() {

    companion object {
        const val AUTHORITY = "org.jarsi.arkphone.mms"
    }
}
