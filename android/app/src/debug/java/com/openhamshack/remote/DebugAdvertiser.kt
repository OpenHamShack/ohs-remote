package com.openhamshack.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Debug builds only: publish a pretend OpenHamShack from the device itself, so
 * discovery can be seen on an emulator, whose NAT never passes the host's
 * mDNS. Started from adb:
 *
 *   adb shell am broadcast -n com.openhamshack.remote/.DebugAdvertiser \
 *       --es name G0ABC --es version 0.9.17 --ei port 18081
 *
 * It advertises the device's own address, so connecting to it reaches
 * nothing unless something on the device listens on that port.
 */
class DebugAdvertiser : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "G0ABC"
        val port = intent.getIntExtra("port", 8080)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_openhamshack._tcp."
            setPort(port)
            setAttribute("name", name)
            setAttribute("version", intent.getStringExtra("version") ?: "0.9.17")
            setAttribute("port", port.toString())
        }
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {}
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        })
    }
}
