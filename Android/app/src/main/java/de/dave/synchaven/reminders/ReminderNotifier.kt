package de.dave.synchaven.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.dave.synchaven.MainActivity
import de.dave.synchaven.R
import de.dave.synchaven.SyncHavenApp
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.di.AppContainer
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.util.dueLocal
import de.dave.synchaven.util.effectiveReminders
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.graphics.toArgb

/**
 * Reminders for events and due tasks as Android notifications. They are based on the local cache ([OfflineData]) – this
 * also works offline and in local mode. Only the next due time is ever scheduled via
 * [AlarmManager]; when it fires, all reminders due since the last check are shown and the
 * next alarm is scheduled. When the data changes, it is rescheduled.
 */
object ReminderNotifier {
    private const val TAG = "ReminderNotifier"
    const val CHANNEL_ID = "reminders"
    internal const val ACTION_ALARM = "de.dave.synchaven.action.REMINDER_ALARM"

    private const val PREFS = "reminders"
    private const val KEY_CHECKED = "checked_at"
    private const val KEY_ACCOUNT = "account"

    /** How far ahead to look for the next reminder */
    private val LOOKAHEAD = Duration.ofDays(8)
    /** At the latest then the app wakes up to fetch new events from the server */
    private val REFRESH = Duration.ofHours(4)
    /** After a longer pause (device off), older reminders are no longer caught up */
    private val MAX_CATCH_UP = Duration.ofMinutes(30)

    private val mutex = Mutex()

    /** Observes data and settings and reschedules on every change (once at app start). */
    @OptIn(FlowPreview::class)
    fun start(context: Context, container: AppContainer) {
        val app = context.applicationContext
        ensureChannel(app)
        container.appScope.launch {
            combine(container.offlineStore.data, container.settingsStore.settings) { d, s -> listOf(d.events, d.calendars, d.tasks, d.taskLists, s.reminders) }
                .distinctUntilChanged()
                .debounce(1_000)
                .collect { runCatching { check(app, container, alarm = false) }.onFailure { Log.w(TAG, "Prüfung fehlgeschlagen", it) } }
        }
    }

    /**
     * Shows all reminders due since the last check and schedules the next alarm.
     * [alarm]: triggered by the alarm – then also request new data from the server.
     */
    suspend fun check(context: Context, container: AppContainer, alarm: Boolean) = mutex.withLock {
        val store = container.offlineStore
        store.awaitReady()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = Instant.now()
        val account = store.currentAccount
        val enabled = container.settingsStore.settings.value.reminders

        if (account == null || !enabled) {
            cancelAlarm(context)
            prefs.edit().putString(KEY_ACCOUNT, account).putLong(KEY_CHECKED, now.toEpochMilli()).apply()
            return@withLock
        }

        // Don't catch up on anything after an account switch
        val checked = if (prefs.getString(KEY_ACCOUNT, null) == account) Instant.ofEpochMilli(prefs.getLong(KEY_CHECKED, now.toEpochMilli())) else now
        val from = maxOf(checked, now.minus(MAX_CATCH_UP))
        val data = store.data.value
        val zone = ZoneId.systemDefault()
        val calendars = data.calendars.orEmpty().associateBy { it.id }
        val events = data.events.orEmpty()

        if (now > from) {
            val due = events.flatMap { ev -> Reminders.due(ev, ev.effectiveReminders(calendars[ev.calendarId]), zone, from, now) }
            for (d in due) notify(context, data, d, calendars[d.event.calendarId]?.color)
            val lists = data.taskLists.orEmpty().associateBy { it.id }
            for (d in Reminders.dueTasks(data.tasks.orEmpty(), zone, from, now)) notifyTask(context, d, lists[d.task.listId]?.color)
        }
        prefs.edit().putString(KEY_ACCOUNT, account).putLong(KEY_CHECKED, now.toEpochMilli()).apply()

        val next = Reminders.next(events, data.calendars.orEmpty(), zone, now, now.plus(LOOKAHEAD))
        val nextTask = Reminders.nextTask(data.tasks.orEmpty(), zone, now, now.plus(LOOKAHEAD))
        scheduleAlarm(context, minOf(next ?: Instant.MAX, nextTask ?: Instant.MAX, now.plus(REFRESH)))

        if (alarm) container.offlineSync.syncIfNeeded(Duration.ofMinutes(30))
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notification_channel_reminders), NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notification_channel_reminders_description) }
        nm.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Exact alarms allowed? (Android 12/12L: can be turned off by the user; from 13 always via USE_EXACT_ALARM) */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun notify(context: Context, data: OfflineData, d: Reminders.Due, color: String?) {
        if (!canNotify(context)) return
        val ev = d.event
        val title = ev.contactId?.let { cid ->
            val year = data.contacts.orEmpty().firstOrNull { it.id == cid }?.birthday
                ?.let { Regex("""^(\d{4})-""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val age = year?.let { d.start.atOffset(ZoneOffset.UTC).year - it }
            if (age != null && age > 0) "${ev.title} ($age)" else ev.title
        } ?: ev.title

        val text = buildString {
            append(describeTime(context, d))
            if (ev.location.isNotBlank()) append(" · ").append(ev.location)
        }
        val open = PendingIntent.getActivity(
            context, d.key.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_calendar_mono)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (ev.description.isBlank()) text else "$text\n${ev.description}"))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(d.at.toEpochMilli())
            .setShowWhen(true)
        color?.let { runCatching { builder.setColor(parseHexColor(it).toArgb()) } }
        try {
            NotificationManagerCompat.from(context).notify(d.key, 1, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Keine Berechtigung für Benachrichtigungen", e)
        }
    }

    private fun notifyTask(context: Context, d: Reminders.TaskDue, color: String?) {
        if (!canNotify(context)) return
        val task = d.task
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val due = task.dueLocal(zone)
        val text = due?.let {
            val day = when (it.toLocalDate()) {
                today -> context.getString(R.string.notification_today)
                today.plusDays(1) -> context.getString(R.string.notification_tomorrow)
                else -> it.toLocalDate().format(FMT_NOTIFICATION_DAY)
            }
            context.getString(R.string.notification_task_due, if (task.allDay) day else "$day, ${it.format(FMT_TIME)}")
        }.orEmpty()
        val open = PendingIntent.getActivity(
            context, d.key.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_calendar_mono)
            .setContentTitle(task.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (task.notes.isBlank()) text else "$text\n${task.notes}"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(d.at.toEpochMilli())
            .setShowWhen(true)
        color?.let { runCatching { builder.setColor(parseHexColor(it).toArgb()) } }
        try {
            NotificationManagerCompat.from(context).notify(d.key, 1, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Keine Berechtigung für Benachrichtigungen", e)
        }
    }

    /** "Heute, 14:00–15:00", "Morgen (ganztägig)", "Fr., 28. Nov., 09:00–10:00" */
    private fun describeTime(context: Context, d: Reminders.Due): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun day(date: LocalDate) = when (date) {
            today -> context.getString(R.string.notification_today)
            today.plusDays(1) -> context.getString(R.string.notification_tomorrow)
            else -> date.format(FMT_NOTIFICATION_DAY)
        }
        if (d.event.allDay) {
            return context.getString(R.string.notification_all_day, day(d.start.atOffset(ZoneOffset.UTC).toLocalDate()))
        }
        val s = d.start.atZone(zone)
        val e = d.end.atZone(zone)
        return if (s.toLocalDate() == e.toLocalDate()) "${day(s.toLocalDate())}, ${s.format(FMT_TIME)}–${e.format(FMT_TIME)}"
        else "${day(s.toLocalDate())}, ${s.format(FMT_TIME)} – ${day(e.toLocalDate())}, ${e.format(FMT_TIME)}"
    }

    private val FMT_NOTIFICATION_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMANY)

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAlarm(context: Context, at: Instant) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val millis = at.toEpochMilli()
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context))
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }
}

/** Reminder alarm as well as reboot, app update and time/time zone change → check and reschedule */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val container = (context.applicationContext as SyncHavenApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                ReminderNotifier.check(context.applicationContext, container, alarm = intent.action == ReminderNotifier.ACTION_ALARM)
            } catch (e: Exception) {
                Log.w("ReminderReceiver", "Erinnerungen konnten nicht geprüft werden", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            ReminderNotifier.ACTION_ALARM,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
