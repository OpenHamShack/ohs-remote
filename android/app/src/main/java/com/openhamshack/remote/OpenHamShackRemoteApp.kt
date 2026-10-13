package com.openhamshack.remote

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.openhamshack.remote.data.Store

class OpenHamShackRemoteApp : Application() {
    /**
     * For requests that must finish even as the screen that sent them goes
     * away - the PTT key-down above all. Lives as long as the process.
     */
    val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { Store(this) }
}
