// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** Receives the PackageInstaller session result for an in-app update. */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.clearManualApk(context)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                val apk = AppUpdater.pendingManualApk(context)
                if (apk != null) {
                    Toast.makeText(context, "Install session failed ($message); opening the system installer", Toast.LENGTH_LONG).show()
                    runCatching { AppUpdater.installManually(context, apk) }
                        .onFailure { Toast.makeText(context, "Could not open the installer: ${it.message}", Toast.LENGTH_LONG).show() }
                } else {
                    Toast.makeText(context, "Update failed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
