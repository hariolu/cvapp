package com.cvapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Home-screen mode: letting CVapp stand in for the launcher, so the phone or a
 * wall tablet boots straight into the dashboard.
 *
 * The HOME intent filter lives on a disabled `activity-alias`, not on
 * MainActivity itself. If it were declared on the activity, every install of
 * CVapp would turn up in Android's "Home app" picker whether or not anyone
 * wanted that — and a stray tap there replaces the user's launcher. The alias
 * ships switched off; enabling it is what puts CVapp in the list.
 *
 * Android decides the rest. Being *offered* as a home app and *being* the home
 * app are different things: only the user picks the default, in Settings, and
 * they can pick something else back at any time. That is also the escape hatch
 * if the dashboard ever misbehaves while it is the home screen — nothing here
 * can take that route away.
 *
 * The enabled state is stored by the package manager, not by us, so it survives
 * app updates and is readable as the single source of truth.
 */
object LauncherMode {

    /** Must match the activity-alias name in AndroidManifest.xml. */
    private const val ALIAS = "com.cvapp.HomeActivity"

    private fun component(context: Context) =
        ComponentName(context.packageName, ALIAS)

    /** True when CVapp appears in Android's list of home apps. */
    fun isOffered(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Adds or removes CVapp from the home-app list.
     *
     * DONT_KILL_APP matters: without it the package manager restarts the process
     * to apply the change, which for the app you are currently looking at means
     * the dialog you just tapped disappears.
     *
     * Removing the alias while CVapp is the *current* home app is safe — the
     * component stops resolving and Android falls back to another launcher.
     */
    fun setOffered(context: Context, offered: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (offered) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    /**
     * True when this activity was launched through the home alias, and so is the
     * one living in Android's dedicated home task.
     *
     * componentName reports the alias an activity was entered through, not the
     * class that implements it, which makes it the one reliable way to tell the
     * two apart from inside a single Activity subclass.
     */
    fun isHomeInstance(activity: Activity): Boolean =
        activity.componentName.className == ALIAS

    /** True when pressing Home right now lands in CVapp. */
    fun isCurrentHome(context: Context): Boolean =
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull() == context.packageName

    /**
     * Android's home-app picker.
     *
     * Deliberately the Settings screen rather than a RoleManager request dialog:
     * a role request can be refused by the platform without showing anything,
     * which would look like the button did nothing. This screen always opens,
     * always lists every home app, and makes the choice explicit.
     */
    fun pickerIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)
}
