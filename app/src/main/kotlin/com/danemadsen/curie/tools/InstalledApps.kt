package com.danemadsen.curie.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/** An app with a launcher icon. */
internal data class LaunchableApp(
    val label: String,
    val component: ComponentName,
)

/**
 * Lists the apps with a launcher icon. On API 30+ this needs the MAIN/LAUNCHER <queries> entry
 * (not QUERY_ALL_PACKAGES); apps in a private space stay invisible.
 */
internal class InstalledApps(private val context: Context) {

    fun launchableApps(): List<LaunchableApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN)
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos = queryActivities(packageManager, launcherIntent)
        return resolveInfos.map { resolveInfo -> toLaunchableApp(packageManager, resolveInfo) }
    }

    private fun queryActivities(packageManager: PackageManager, intent: Intent): List<ResolveInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val noFlags = PackageManager.ResolveInfoFlags.of(0L)
            return packageManager.queryIntentActivities(intent, noFlags)
        }
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, 0)
    }

    private fun toLaunchableApp(packageManager: PackageManager, resolveInfo: ResolveInfo): LaunchableApp {
        val activityInfo = resolveInfo.activityInfo
        val label = resolveInfo.loadLabel(packageManager).toString()
        val component = ComponentName(activityInfo.packageName, activityInfo.name)
        return LaunchableApp(label, component)
    }
}

/** Finds the app a spoken name means: "you tube", "the maps app", "spotfy". */
internal object AppMatcher {
    /** The lowest [NameMatching] score that still counts as the app the user meant. */
    const val MIN_SCORE = 0.5

    /** Spelt at least this much alike, an app is worth suggesting. */
    private const val MIN_SUGGESTION_SIMILARITY = 0.5

    private val LEADING_FILLERS = setOf("the", "my")
    private val TRAILING_FILLERS = setOf("app", "application")

    /** The best match, or null when nothing scores [MIN_SCORE]; a tie goes to the shorter label. */
    fun bestMatch(spokenName: String, apps: List<LaunchableApp>): LaunchableApp? {
        val ranked = rank(spokenName, apps)
        val best = ranked.firstOrNull() ?: return null
        if (best.second < MIN_SCORE) {
            return null
        }
        return best.first
    }

    /** Up to [limit] labels that loosely resemble [spokenName], best first, for "did you mean". */
    fun suggestions(spokenName: String, apps: List<LaunchableApp>, limit: Int): List<String> {
        val scored = apps.map { app ->
            val matchScore = NameMatching.score(spokenName, app.label)
            val spellingScore = NameMatching.looseSimilarity(spokenName, app.label)
            val bestScore = maxOf(matchScore, spellingScore)
            app to bestScore
        }
        val plausible = scored.filter { (_, score) -> score >= MIN_SUGGESTION_SIMILARITY }
        val ranked = plausible.sortedByDescending { (_, score) -> score }
        val labels = ranked.map { (app, _) -> app.label }
        val distinctLabels = labels.distinct()
        return distinctLabels.take(limit)
    }

    private fun rank(spokenName: String, apps: List<LaunchableApp>): List<Pair<LaunchableApp, Double>> {
        val spokenVariants = spokenVariants(spokenName)
        val scored = apps.map { app ->
            val score = spokenVariants.maxOf { variant -> NameMatching.score(variant, app.label) }
            app to score
        }

        val byScore = compareByDescending<Pair<LaunchableApp, Double>> { (_, score) -> score }
        val byLength = byScore.thenBy { (app, _) -> app.label.length }
        val byLabel = byLength.thenBy { (app, _) -> app.label }
        return scored.sortedWith(byLabel)
    }

    /** The name as said, and without filler words ("the YouTube app" -> "YouTube"). */
    private fun spokenVariants(spokenName: String): List<String> {
        val words = NameMatching.words(spokenName)
        var first = 0
        var last = words.size
        while (first < last - 1 && words[first] in LEADING_FILLERS) {
            first += 1
        }
        while (last - 1 > first && words[last - 1] in TRAILING_FILLERS) {
            last -= 1
        }

        val withoutFillers = words.subList(first, last).joinToString(" ")
        return listOf(spokenName, withoutFillers).distinct()
    }
}
