package app.raspisanie

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Экран блокировки Samsung: 1x1 (номер урока и время). */
class LockWidgetTiny : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidget.refresh(context)
    }
}

/** Экран блокировки Samsung: 2x1 (иконка слева, три строки текста). */
class LockWidgetWide : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidget.refresh(context)
    }
}

/** Экран блокировки Samsung: 2x2 (карточка, иконка сверху). */
class LockWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidget.refresh(context)
    }
}

/** SystemUI Samsung просит у приложения RemoteViews для экрана блокировки и AOD. */
class LockServiceBoxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockViews.ACTION_REQUEST) return
        val requested = intent.getStringExtra(LockViews.EXTRA_PAGE_ID)
        val ids = when {
            requested == null -> LockViews.PAGE_IDS
            requested in LockViews.PAGE_IDS -> listOf(requested)
            else -> return
        }
        val now = LocalDateTime.now()
        val st = Schedule.state(context, now)
        ids.forEach { LockViews.sendServiceBox(context, it, st, now) }
    }
}

object LockViews {
    const val ACTION_REQUEST = "com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS"
    private const val ACTION_RESPONSE = "com.samsung.android.intent.action.RESPONSE_SERVICEBOX_REMOTEVIEWS"
    const val EXTRA_PAGE_ID = "pageId"

    const val PAGE_TINY = "raspisanie_lock_1x1"
    const val PAGE_WIDE = "raspisanie_lock_2x1"
    const val PAGE_SMALL = "raspisanie_lock_2x2"
    val PAGE_IDS = listOf(PAGE_TINY, PAGE_WIDE, PAGE_SMALL)

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

    fun tiny(c: Context, st: State, now: LocalDateTime): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.lock_tiny)
        val item = pick(st).item
        if (item == null) {
            rv.setTextViewText(R.id.tvTitle, "-")
            rv.setTextViewText(R.id.tvSub, "")
        } else {
            val num = item.label.takeWhile { it.isDigit() }
            rv.setTextViewText(R.id.tvTitle, if (num.isNotEmpty()) num else item.title.take(2))
            val t = if (st.current != null) item.endAt else item.startAt
            rv.setTextViewText(R.id.tvSub, t.format(hm))
        }
        rv.setOnClickPendingIntent(R.id.root, openApp(c))
        return rv
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
        } else {
            rv.setTextViewText(R.id.tvTitle, item.title)
            rv.setTextViewText(R.id.tvSub, timeLine(item, today, true))
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

    fun forPage(c: Context, pageId: String, st: State, now: LocalDateTime): RemoteViews =
        when (pageId) {
            PAGE_TINY -> tiny(c, st, now)
            PAGE_WIDE -> wide(c, st, now)
            else -> small(c, st, now)
        }

    fun sendServiceBox(c: Context, pageId: String, st: State, now: LocalDateTime) {
        val rv = forPage(c, pageId, st, now)
        c.sendBroadcast(
            Intent(ACTION_RESPONSE).apply {
                setPackage("com.android.systemui")
                putExtra("package", c.packageName)
                putExtra(EXTRA_PAGE_ID, pageId)
                putExtra("show", true)
                putExtra("origin", rv)
                putExtra("aod", rv)
            }
        )
    }

    fun pushServiceBox(c: Context, st: State, now: LocalDateTime) {
        PAGE_IDS.forEach { sendServiceBox(c, it, st, now) }
    }
}
