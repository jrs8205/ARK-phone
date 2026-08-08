package org.jarsi.arkphone.voip

/**
 * Called once per process. Bound only in builds that carry the VoIP engine, so
 * a release build starts nothing and opens no socket.
 */
interface VoipStartup {
    fun onAppStart()
}
