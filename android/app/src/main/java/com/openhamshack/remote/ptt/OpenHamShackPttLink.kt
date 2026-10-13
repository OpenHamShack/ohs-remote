package com.openhamshack.remote.ptt

import com.openhamshack.remote.api.OpenHamShackClient

/** The remote lease over the console API. */
class OpenHamShackPttLink(private val client: OpenHamShackClient) : PttLink {
    override suspend fun key(remote: String, force: Boolean): KeyAnswer {
        val st = client.ptt(on = true, remote = remote, force = force)
        // A refusal is 200 like a success (licence, callsign, source, busy
        // QO-100, relay, radio not answering): onAir false says it, txError
        // says why. Only on air under our own lease counts as keyed.
        val ours = st.lease?.id == remote
        return when {
            st.onAir && ours -> KeyAnswer(error = null)
            st.onAir -> {
                // On air with no dead-man of ours: nothing would stop it if
                // this phone vanished. Take it straight down.
                runCatching { client.ptt(on = false) }
                KeyAnswer(error = "OpenHamShack went on air without this phone's lease, so it was stopped")
            }
            else -> KeyAnswer(error = st.txError.ifBlank { "OpenHamShack did not go on air" }, check = st.txCheck)
        }
    }

    override suspend fun heartbeat(remote: String): Boolean = client.heartbeat(remote)

    override suspend fun unkey() {
        client.ptt(on = false)
    }
}
