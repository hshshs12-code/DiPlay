// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: opens the head unit's MediaTek EngineerMode app, which edits the HAL volume table.
package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent

/**
 * MediaTek units ship EngineerMode (com.mediatek.engineermode) with its main activity exported.
 * Its Audio pages read and write the HAL's per-step volume table (see [HalVolumeTable]) and
 * apply it live with `ReloadAudioVolume`. The Audio pages themselves are not exported, so DiPlay
 * can only open the main menu; the user taps Audio from there.
 */
object EngineerMode {
    const val PACKAGE = "com.mediatek.engineermode"
    private const val ACTIVITY = "$PACKAGE.EngineerMode"

    const val HINT = "Opens the unit's MediaTek EngineerMode. Tap Audio, then LoudSpeaker Mode (the speaker path). " +
        "Type Media is music; levels 0–14 are the per-step gains (0–255), two knob steps per level. " +
        "Write the original numbers down, change one at a time, press Set; it applies live. " +
        "Normal Mode is the line/headphone path. Nothing here is reversible by DiPlay."

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun open(context: Context): Result<Unit> = runCatching {
        context.startActivity(Intent(Intent.ACTION_MAIN).setClassName(PACKAGE, ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
