/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches which TV launcher alias is visible so its banner matches the user's selection. */
object LauncherBranding {
    private val aliases = mapOf(
        AppSettings.LauncherStyle.BLUE to "app.opentv.LauncherBlue",
        AppSettings.LauncherStyle.SUNSET to "app.opentv.LauncherSunset",
        AppSettings.LauncherStyle.ICE to "app.opentv.LauncherIce",
        AppSettings.LauncherStyle.NEON to "app.opentv.LauncherNeon",
        AppSettings.LauncherStyle.CLASSIC to "app.opentv.LauncherClassic",
        AppSettings.LauncherStyle.RED to "app.opentv.LauncherRed",
    )

    fun apply(context: Context, selected: AppSettings.LauncherStyle): Boolean = runCatching {
        val manager = context.packageManager
        manager.setComponentEnabledSetting(
            ComponentName(context.packageName, aliases.getValue(selected)),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        aliases.filterKeys { it != selected }.values.forEach { className ->
            manager.setComponentEnabledSetting(
                ComponentName(context.packageName, className),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }.isSuccess
}
