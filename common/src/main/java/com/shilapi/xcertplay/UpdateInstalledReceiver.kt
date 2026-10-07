// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the system replaced DiPlay with a downloaded update: delete the APK and reopen DiPlay. */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val updated = AppUpdater.pendingApk(context) != null
        AppUpdater.cleanup(context)
        AppUpdater.clearManualApk(context)
        if (updated) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
