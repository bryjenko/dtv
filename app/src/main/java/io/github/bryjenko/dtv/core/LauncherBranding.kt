/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the phone and TV launcher aliases so their icons match the user's selection. */
object LauncherBranding {
    private val aliases = mapOf(
        AppSettings.LauncherStyle.BLUE to "io.github.bryjenko.dtv.LauncherBlue",
        AppSettings.LauncherStyle.SUNSET to "io.github.bryjenko.dtv.LauncherSunset",
        AppSettings.LauncherStyle.ICE to "io.github.bryjenko.dtv.LauncherIce",
        AppSettings.LauncherStyle.NEON to "io.github.bryjenko.dtv.LauncherNeon",
        AppSettings.LauncherStyle.CLASSIC to "io.github.bryjenko.dtv.LauncherClassic",
        AppSettings.LauncherStyle.RED to "io.github.bryjenko.dtv.LauncherRed",
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
