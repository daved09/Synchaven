package de.dave.synchaven.icon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting
import android.os.Build
import de.dave.synchaven.MainActivity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Launcher-Icon mit dem aktuellen Tag des Monats (wie Google Kalender).
 *
 * Android kann ein Launcher-Icon nicht zur Laufzeit zeichnen. Stattdessen gibt es im Manifest
 * je Tag einen `activity-alias` (LauncherDay1–31) mit eigenem Icon; aktiviert ist immer genau
 * einer. Die Icons erzeugt `Android/tools/generate_day_icons.py`.
 */
object DynamicLauncherIcon {
    internal const val ACTION_DAY_CHANGED = "de.dave.synchaven.action.DAY_CHANGED"
    private val PACKAGE = MainActivity::class.java.name.substringBeforeLast('.')
    private val DEFAULT_ALIAS = "$PACKAGE.LauncherDefault"
    private fun dayAlias(day: Int) = "$PACKAGE.LauncherDay$day"

    /** Aktiviert den Alias für heute und plant die nächste Aktualisierung kurz nach Mitternacht. */
    fun update(context: Context) {
        val app = context.applicationContext
        applyDay(app, LocalDate.now().dayOfMonth)
        scheduleNextUpdate(app)
    }

    private fun applyDay(context: Context, today: Int) {
        val pm = context.packageManager
        // Gewünschter Zustand je Alias; DEFAULT = Manifestwert (Tages-Aliasse sind dort deaktiviert).
        val wanted = buildMap {
            put(DEFAULT_ALIAS, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            for (day in 1..31) {
                put(dayAlias(day),
                    if (day == today) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            }
        }
        // Nur echte Änderungen schreiben – jede löst beim Launcher ein Neuladen der App aus.
        // Den neuen Alias zuerst aktivieren, damit nie kein Launcher-Eintrag existiert.
        val changes = wanted
            .map { (cls, state) -> ComponentName(context.packageName, cls) to state }
            .filter { (cn, state) -> pm.getComponentEnabledSetting(cn) != state }
            .sortedByDescending { (_, state) -> state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        if (changes.isEmpty()) return

        val flags = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.setComponentEnabledSettings(changes.map { (cn, state) -> ComponentEnabledSetting(cn, state, flags) })
        } else {
            changes.forEach { (cn, state) -> pm.setComponentEnabledSetting(cn, state, flags) }
        }
    }

    private fun scheduleNextUpdate(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val nextMidnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
        // Inexakt (keine Berechtigung für exakte Alarme nötig); wenige Minuten Verzug sind egal.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC,
            nextMidnight.toInstant().toEpochMilli() + 30_000,
            pendingIntent(context),
        )
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DayChangeReceiver::class.java).setAction(ACTION_DAY_CHANGED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Mitternachts-Alarm sowie Neustart, App-Update und Zeit-/Zeitzonenänderung. */
class DayChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in HANDLED_ACTIONS) DynamicLauncherIcon.update(context)
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            DynamicLauncherIcon.ACTION_DAY_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
