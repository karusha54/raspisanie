package app.raspisanie

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> refresh(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "app.raspisanie.REFRESH"

        private fun alarmManager(c: Context) = c.getSystemService(AlarmManager::class.java)

        private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
            c, 0,
            Intent(c, ScheduleWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun refresh(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val now = LocalDateTime.now()
            val st = Schedule.state(c, now)
            var any = false

            val home = mgr.getAppWidgetIds(ComponentName(c, ScheduleWidget::class.java))
            if (home.isNotEmpty()) {
                mgr.updateAppWidget(home, build(c, st, now))
                any = true
            }
            var lock = false
            val tiny = mgr.getAppWidgetIds(ComponentName(c, LockWidgetTiny::class.java))
            if (tiny.isNotEmpty()) {
                mgr.updateAppWidget(tiny, LockViews.tiny(c, st, now))
                lock = true
            }
            val wide = mgr.getAppWidgetIds(ComponentName(c, LockWidgetWide::class.java))
            if (wide.isNotEmpty()) {
                mgr.updateAppWidget(wide, LockViews.wide(c, st, now))
                lock = true
            }
            val small = mgr.getAppWidgetIds(ComponentName(c, LockWidgetSmall::class.java))
            if (small.isNotEmpty()) {
                mgr.updateAppWidget(small, LockViews.small(c, st, now))
                lock = true
            }
            val xl = mgr.getAppWidgetIds(ComponentName(c, LockWidgetXl::class.java))
            if (xl.isNotEmpty()) {
                mgr.updateAppWidget(xl, LockViews.xl(c, st, now))
                lock = true
            }
            if (lock) {
                LockViews.pushServiceBox(c, st, now)
                any = true
            }
            if (any) scheduleNext(c, st.nextChange)
        }

        private fun scheduleNext(c: Context, at: LocalDateTime) {
            val am = alarmManager(c)
            val pi = pending(c)
            val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 500
            try {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }

        private fun build(c: Context, st: State, now: LocalDateTime): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget)
            val today = now.toLocalDate()

            val primary: Item?
            val secondary: Item?
            val status: String
            val secLabel: String
            if (st.current != null) {
                primary = st.current
                secondary = st.upcoming.firstOrNull()
                status = "Сейчас"
                secLabel = "След: "
            } else {
                primary = st.upcoming.firstOrNull()
                secondary = st.upcoming.getOrNull(1)
                status = if (primary != null && primary.startAt.toLocalDate() == today)
                    "Перемена · дальше" else "Дальше"
                secLabel = "Потом: "
            }

            if (primary == null) {
                rv.setTextViewText(R.id.tvStatus, "Расписание")
                rv.setTextViewText(R.id.tvTitle, "Нет уроков")
                rv.setTextViewText(R.id.tvSub, "Добавь расписание в приложении")
                rv.setViewVisibility(R.id.divider, View.GONE)
                rv.setViewVisibility(R.id.tvNextTitle, View.GONE)
                rv.setViewVisibility(R.id.tvNextSub, View.GONE)
            } else {
                rv.setTextViewText(R.id.tvStatus, status)
                rv.setTextViewText(R.id.tvTitle, primary.title)
                rv.setTextViewText(
                    R.id.tvSub,
                    Schedule.dayPrefix(primary.startAt.toLocalDate(), today) + Schedule.sub(primary)
                )
                if (secondary == null) {
                    rv.setViewVisibility(R.id.divider, View.GONE)
                    rv.setViewVisibility(R.id.tvNextTitle, View.GONE)
                    rv.setViewVisibility(R.id.tvNextSub, View.GONE)
                } else {
                    rv.setViewVisibility(R.id.divider, View.VISIBLE)
                    rv.setViewVisibility(R.id.tvNextTitle, View.VISIBLE)
                    rv.setViewVisibility(R.id.tvNextSub, View.VISIBLE)
                    rv.setTextViewText(R.id.tvNextTitle, secLabel + secondary.title)
                    rv.setTextViewText(
                        R.id.tvNextSub,
                        Schedule.dayPrefix(secondary.startAt.toLocalDate(), today) + Schedule.sub(secondary)
                    )
                }
            }

            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.root, open)
            return rv
        }
    }
}
