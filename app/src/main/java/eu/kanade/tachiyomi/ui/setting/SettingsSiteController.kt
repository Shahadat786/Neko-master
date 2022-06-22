package eu.kanade.tachiyomi.ui.setting

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.jobs.follows.StatusSyncJob
import eu.kanade.tachiyomi.jobs.migrate.V5MigrationJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.handlers.FollowsHandler
import eu.kanade.tachiyomi.source.online.utils.FollowStatus
import eu.kanade.tachiyomi.source.online.utils.MdLang
import eu.kanade.tachiyomi.source.online.utils.MdUtil
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.util.system.executeOnIO
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.widget.preference.MangadexLoginDialog
import eu.kanade.tachiyomi.widget.preference.MangadexLogoutDialog
import eu.kanade.tachiyomi.widget.preference.SiteLoginPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsSiteController :
    SettingsController(),
    MangadexLoginDialog.Listener,
    MangadexLogoutDialog.Listener {

    private val mdex by lazy { Injekt.get<SourceManager>().getMangadex() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.site_specific_settings

        val sourcePreference = SiteLoginPreference(context, mdex).apply {
            title = mdex.name + " Login"

            this.username = preferences.sourceUsername(mdex) ?: ""

            key = getSourceKey(source.id)
            setOnLoginClickListener {
                if (mdex.isLogged()) {
                    val dialog = MangadexLogoutDialog(source)
                    dialog.targetController = this@SettingsSiteController
                    dialog.showDialog(router)
                } else {
                    val dialog = MangadexLoginDialog(source)
                    dialog.targetController = this@SettingsSiteController
                    dialog.showDialog(router)
                }
            }
            this.isIconSpaceReserved = false
        }

        addPreference(sourcePreference)

        preference {
            titleRes = R.string.show_languages
            onClick {
                val ctrl = ChooseLanguagesDialog(preferences)
                ctrl.targetController = this@SettingsSiteController
                ctrl.showDialog(router)
            }
        }

        multiSelectListPreferenceMat(activity) {
            key = PreferenceKeys.contentRating
            titleRes = R.string.content_rating_title
            summaryRes = R.string.content_rating_summary
            entriesRes = arrayOf(
                R.string.content_rating_safe,
                R.string.content_rating_suggestive,
                R.string.content_rating_erotica,
                R.string.content_rating_pornographic,
            )
            entryValues = listOf(
                "safe",
                "suggestive",
                "erotica",
                "pornographic",
            )

            defValue = setOf("safe", "suggestive")

            defaultValue = listOf("safe", "suggestive")
        }

        switchPreference {
            key = PreferenceKeys.showContentRatingFilter
            titleRes = R.string.show_content_rating_filter_in_search
            defaultValue = true
        }

        switchPreference {
            key = PreferenceKeys.enablePort443Only
            titleRes = R.string.use_port_443_title
            summaryRes = R.string.use_port_443_summary
            defaultValue = true
        }

        switchPreference {
            key = PreferenceKeys.dataSaver
            titleRes = R.string.data_saver
            summaryRes = R.string.data_saver_summary
            defaultValue = false
        }

        intListPreference(activity) {
            key = PreferenceKeys.thumbnailQuality
            titleRes = R.string.thumbnail_quality
            entriesRes = arrayOf(
                R.string.original_thumb,
                R.string.medium_thumb,
                R.string.low_thumb,
            )
            entryRange = 0..2
            defaultValue = 0
        }

        switchPreference {
            key = PreferenceKeys.readingSync
            titleRes = R.string.reading_sync
            summaryRes = R.string.reading_sync_summary
            defaultValue = false
        }

        preference {
            titleRes = R.string.sync_follows_to_library
            summaryRes = R.string.sync_follows_to_library_summary

            onClick {
                activity!!.materialAlertDialog()
                    .setNegativeButton(android.R.string.cancel, null)
                    .setMultiChoiceItems(
                        context.resources.getStringArray(R.array.follows_options).drop(1)
                            .toTypedArray(),
                        booleanArrayOf(true, false, false, false, false, true),
                    ) { dialog, position, bool ->
                        val listView = (dialog as AlertDialog).listView
                        listView.setItemChecked(position, bool)
                    }
                    .setPositiveButton(android.R.string.ok) { dialog, t ->
                        val listView = (dialog as AlertDialog).listView
                        val indiciesSelected = mutableListOf<String>()
                        for (i in 0 until listView.count) {
                            if (listView.isItemChecked(i)) {
                                indiciesSelected.add((i + 1).toString())
                            }
                        }
                        if (indiciesSelected.size > 0) {
                            preferences.mangadexSyncToLibraryIndexes()
                                .set(indiciesSelected.toSet())
                            StatusSyncJob.doWorkNow(context, StatusSyncJob.entireFollowsFromDex)
                        }
                    }
                    .show()
            }
        }

        preference {
            titleRes = R.string.push_favorites_to_mangadex
            summaryRes = R.string.push_favorites_to_mangadex_summary

            onClick {
                StatusSyncJob.doWorkNow(context, StatusSyncJob.entireLibraryToDex)
            }
        }

        if (BuildConfig.DEBUG) {
            preference {
                title = "Unfollow all library manga"
                onClick {
                    launchIO {
                        val db = Injekt.get<DatabaseHelper>()
                        val followsHandler = Injekt.get<FollowsHandler>()
                        val trackManager: TrackManager = Injekt.get()
                        db.getLibraryMangaList().executeAsBlocking().forEach {
                            followsHandler.updateFollowStatus(
                                MdUtil.getMangaId(it.url),
                                FollowStatus.UNFOLLOWED,
                            )
                            db.getMDList(it).executeOnIO()?.let { _ ->
                                db.deleteTrackForManga(it, trackManager.mdList)
                                    .executeAsBlocking()
                            }
                        }
                    }
                }
            }
        }

        switchPreference {
            key = PreferenceKeys.addToLibraryAsPlannedToRead
            titleRes = R.string.add_favorites_as_planned_to_read
            summaryRes = R.string.add_favorites_as_planned_to_read_summary
            defaultValue = false
        }

        preference {
            titleRes = R.string.v5_migration_service
            summary = context.resources.getString(R.string.v5_migration_desc)
            onClick {
                context.materialAlertDialog()
                    .setTitle(R.string.v5_migration_notice)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        V5MigrationJob.doWorkNow(activity!!)
                    }
                    .show()
            }
        }
    }

    override fun siteLoginDialogClosed(source: Source, username: String) {
        val pref = findPreference(getSourceKey(source.id)) as? SiteLoginPreference
        pref?.username = username
        pref?.notifyChanged()
    }

    override fun siteLogoutDialogClosed(source: Source, username: String) {
        val pref = findPreference(getSourceKey(source.id)) as? SiteLoginPreference
        pref?.username = username
        pref?.notifyChanged()
    }

    private fun getSourceKey(sourceId: Long): String {
        return "source_$sourceId"
    }

    class ChooseLanguagesDialog() : DialogController() {

        constructor(preferences: PreferencesHelper) : this() {
            this.preferences = preferences
        }

        var preferences: PreferencesHelper? = null

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val activity = activity!!

            val options = MdLang.values().map { Pair(it.lang, it.prettyPrint) }

            val initialLangs = preferences!!.langsToShow().get().split(",")
                .map { lang -> options.indexOfFirst { it.first == lang } }.toIntArray()

            val allLangs = (options.map { it.second }).toTypedArray()
            val enabledLangs =
                (options.mapIndexed { index, _ -> initialLangs.contains(index) }).toBooleanArray()

            return activity.materialAlertDialog()
                .setTitle(R.string.show_languages)
                .setMultiChoiceItems(
                    allLangs,
                    enabledLangs,
                ) { dialog, position, _ ->
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { dialog, t ->
                    val listView = (dialog as AlertDialog).listView
                    val selected = mutableListOf<String>()
                    for (i in 0 until listView.count) {
                        if (listView.isItemChecked(i)) {
                            selected.add(options[i].first)
                        }
                    }
                    preferences!!.langsToShow().set(selected.joinToString(","))
                }
                .create()
        }
    }
}
