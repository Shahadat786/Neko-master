package eu.kanade.tachiyomi.data.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.toast

class AppUpdateBroadcast : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppUpdateService.PACKAGE_INSTALLED_ACTION == intent.action) {
            val extras = intent.extras ?: return
            when (val status = extras.getInt(PackageInstaller.EXTRA_STATUS)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmIntent = extras[Intent.EXTRA_INTENT] as? Intent
                    context.startActivity(confirmIntent?.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    prefs.edit {
                        remove(AppUpdateService.NOTIFY_ON_INSTALL_KEY)
                    }
                    val notifyOnInstall = extras.getBoolean(AppUpdateService.EXTRA_NOTIFY_ON_INSTALL, false)
                    try {
                        if (notifyOnInstall) {
                            AppUpdateNotifier(context).onInstallFinished()
                        }
                    } finally {
                        AppUpdateService.stop(context)
                    }
                }
                PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED, PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                        context.toast(R.string.could_not_install_update)
                        val uri = intent.getStringExtra(AppUpdateService.EXTRA_FILE_URI) ?: return
                        AppUpdateNotifier(context).onInstallError(uri.toUri())
                    }
                }
            }
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val notifyOnInstall = prefs.getBoolean(AppUpdateService.NOTIFY_ON_INSTALL_KEY, false)
            prefs.edit {
                remove(AppUpdateService.NOTIFY_ON_INSTALL_KEY)
            }
            if (notifyOnInstall) {
                AppUpdateNotifier(context).onInstallFinished()
            }
        }
    }
}
