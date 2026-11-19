package app.raspisanie

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Виджет для экрана блокировки 4x2 (иконка слева, текст справа). */
class LockWidgetWide : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidget.refresh(context)
    }
}

/** Виджет для экрана блокировки 2x2 (иконка сверху, текст по центру). */
class LockWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidget.refresh(context)
    }
}

object LockViews {
    private val hm = DateTimeFormatter.ofPattern("H:mm")

    private class Pick(val label: String, val item: Item?)

    private fun pick(st: State): Pick = when {
        st.current != null -> Pick("Сейчас:", st.current)
        st.upcoming.isNotEmpty() -> Pick("Дальше:", st.upcoming[0])
        else -> Pick("Расписание", null)
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun timeLine(item: Item, today: LocalDate, withRoom: Boolean): String {
        val base = Schedule.dayPrefix(item.startAt.toLocalDate(), today) + Schedule.time(item)
        return if (withRoom && item.room.isNotBlank()) "$base / каб. ${item.room}" else base
    }

    fun wide(c: Context, st: State, now: LocalDateTime): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.lock_wide)
        val today = now.toLocalDate()
        val p = pick(st)
        val item = p.item
        rv.setTextViewText(R.id.tvStatus, p.label)
        if (item == null) {
            rv.setTextViewText(R.id.tvTitle, "Нет уроков")
            rv.setTextViewText(R.id.tvSub, "")
            rv.setViewVisibility(R.id.tvNext, View.GONE)
        } else {
            rv.setTextViewText(R.id.tvTitle, item.title)
            rv.setTextViewText(R.id.tvSub, timeLine(item, today, true))
            val nxt = if (st.current != null) st.upcoming.firstOrNull() else null
            if (nxt != null) {
                rv.setViewVisibility(R.id.tvNext, View.VISIBLE)
                rv.setTextViewText(
                    R.id.tvNext,
                    "След: ${nxt.title}, " + Schedule.dayPrefix(nxt.startAt.toLocalDate(), today) +
                        nxt.startAt.format(hm)
                )
            } else {
                rv.setViewVisibility(R.id.tvNext, View.GONE)
            }
        }
        rv.setOnClickPendingIntent(R.id.root, openApp(c))
        return rv
    }

    fun small(c: Context, st: State, now: LocalDateTime): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.lock_small)
        val today = now.toLocalDate()
        val p = pick(st)
        val item = p.item
        rv.setTextViewText(R.id.tvStatus, p.label)
        if (item == null) {
            rv.setTextViewText(R.id.tvTitle, "Нет уроков")
            rv.setTextViewText(R.id.tvSub, "")
            rv.setViewVisibility(R.id.tvRoom, View.GONE)
        } else {
            rv.setTextViewText(R.id.tvTitle, item.title)
            rv.setTextViewText(R.id.tvSub, timeLine(item, today, false))
            if (item.room.isBlank()) {
                rv.setViewVisibility(R.id.tvRoom, View.GONE)
            } else {
                rv.setViewVisibility(R.id.tvRoom, View.VISIBLE)
                rv.setTextViewText(R.id.tvRoom, "каб. ${item.room}")
            }
        }
        rv.setOnClickPendingIntent(R.id.root, openApp(c))
        return rv
    }
}
