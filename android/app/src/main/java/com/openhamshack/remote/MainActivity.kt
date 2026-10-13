package com.openhamshack.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.openhamshack.remote.ui.OpenHamShackRemoteUi
import com.openhamshack.remote.ui.TvUi
import com.openhamshack.remote.ui.isTelevision

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // ACTION_SCREEN_OFF arrives as the power button is pressed; onStop can
    // follow a moment later. A carrier should not wait for the lifecycle.
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) vm.screenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        // One APK for both: a TV gets the receive-only UI, decided once -
        // a device does not stop being a TV while the app runs.
        val tv = isTelevision(this)
        setContent { if (tv) TvUi(vm) else OpenHamShackRemoteUi(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.foreground(true)
    }

    override fun onStop() {
        // Not visible any more (home, another app, the screen locked, a call):
        // the transmitter, the camera and the picture all stop here.
        vm.foreground(false)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }
}
