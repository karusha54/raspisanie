package app.raspisanie

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class Bell(val lesson: Int?, val name: String, val start: Int, val end: Int)

data class Item(
    val title: String,
    val room: String,
    val label: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime
)

data class State(val current: Item?, val upcoming: List<Item>, val nextChange: LocalDateTime)

/** Сетка звонков. Если что-то поменяется - правится только здесь. */
object Bells {
    private fun m(h: Int, min: Int) = h * 60 + min
    private fun l(n: Int, h1: Int, m1: Int, h2: Int, m2: Int) =
        Bell(n, "$n урок", m(h1, m1), m(h2, m2))

    private val standard = listOf(
        l(1, 8, 0, 8, 45), l(2, 8, 55, 9, 40), l(3, 9, 55, 10, 40),
        l(4, 10, 55, 11, 40), l(5, 11, 55, 12, 40), l(6, 12, 50, 13, 35)
    )

    private val monday = listOf(
        Bell(null, "Классный час", m(8, 0), m(8, 30)),
        l(1, 8, 35, 9, 15), l(2, 9, 25, 10, 5), l(3, 10, 15, 10, 55),
        l(4, 11, 10, 11, 50), l(5, 12, 5, 12, 45), l(6, 12, 55, 13, 35)
    )

    private val shortLessons = listOf(
        l(1, 8, 0, 8, 40), l(2, 8, 50, 9, 30), l(3, 9, 40, 10, 20),
        l(4, 10, 35, 11, 15), l(5, 12, 5, 12, 45), l(6, 12, 55, 13, 35)
    )
    private val prof = Bell(null, "Профминимум", m(11, 30), m(12, 0))
    private val thursday = shortLessons.take(4) + prof + shortLessons.drop(4)

    fun forDay(d: DayOfWeek): List<Bell> = when (d) {
        DayOfWeek.MONDAY -> monday
        DayOfWeek.THURSDAY -> thursday
        DayOfWeek.SATURDAY -> shortLessons // как в четверг, без профминимума/кл. часа
        DayOfWeek.SUNDAY -> emptyList()
        else -> standard
    }
}

/** Хранилище: расписание по датам. Строка дня: "Предмет|каб\n..." (6 строк). */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)
    private fun key(d: LocalDate) = "d:$d"

    fun load(c: Context, d: LocalDate): List<Pair<String, String>> {
        val raw = prefs(c).getString(key(d), null) ?: return emptyList()
        return raw.split("\n").map { line ->
            val p = line.split("|")
            p.getOrElse(0) { "" } to p.getOrElse(1) { "" }
        }
    }

    fun save(c: Context, d: LocalDate, rows: List<Pair<String, String>>) {
        fun clean(s: String) = s.replace("|", " ").replace("\n", " ").trim()
        val raw = rows.joinToString("\n") { clean(it.first) + "|" + clean(it.second) }
        prefs(c).edit().putString(key(d), raw).apply()
    }

    /** Первый запуск: расписание с фото на 17-19 ноября. */
    fun seed(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("seeded", false)) return
        fun day(vararg r: String) = r.joinToString("\n")
        p.edit()
            .putString(key(LocalDate.of(2026, 11, 17)), day(
                "Ин. язык|217/220", "ОБЗР|108", "Физкультура|", "История|302",
                "Гос. язык|312/201б", "Геометрия|212"))
            .putString(key(LocalDate.of(2026, 11, 18)), day(
                "Биология|106", "Рус. язык|212", "География|208", "Гос. язык|101/201б",
                "Физкультура|", "Ин. язык|217/220"))
            .putString(key(LocalDate.of(2026, 11, 19)), day(
                "Ин. язык|217/220", "Физкультура|", "Литература|212", "Химия|205",
                "Физика|201", "Геометрия|108"))
            .putBoolean("seeded", true)
            .apply()
    }
}

object Schedule {
    private val tf = DateTimeFormatter.ofPattern("H:mm")
    private val ru = Locale.forLanguageTag("ru")

    fun itemsFor(c: Context, d: LocalDate): List<Item> {
        val rows = Store.load(c, d)
        if (rows.none { it.first.isNotBlank() }) return emptyList()
        val out = ArrayList<Item>()
        for (b in Bells.forDay(d.dayOfWeek)) {
            val s = d.atTime(b.start / 60, b.start % 60)
            val e = d.atTime(b.end / 60, b.end % 60)
            if (b.lesson == null) {
                out += Item(b.name, "", "", s, e)
            } else {
                val r = rows.getOrNull(b.lesson - 1) ?: continue
                if (r.first.isBlank()) continue
                out += Item(r.first, r.second, b.name, s, e)
            }
        }
        return out
    }

    fun state(c: Context, now: LocalDateTime): State {
        val all = (0..7).flatMap { itemsFor(c, now.toLocalDate().plusDays(it.toLong())) }
            .filter { it.endAt.isAfter(now) }
            .sortedBy { it.startAt }
        val cur = all.firstOrNull { !it.startAt.isAfter(now) }
        val upcoming = all.filter { it.startAt.isAfter(now) }
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val change = cur?.endAt ?: upcoming.firstOrNull()?.startAt ?: midnight
        return State(cur, upcoming, if (change.isBefore(midnight)) change else midnight)
    }

    fun time(i: Item) = "${i.startAt.format(tf)} – ${i.endAt.format(tf)}"

    fun sub(i: Item): String {
        val base = if (i.label.isEmpty()) time(i) else "${i.label}, ${time(i)}"
        return if (i.room.isBlank()) base else "$base · каб. ${i.room}"
    }

    fun dayPrefix(d: LocalDate, today: LocalDate): String = when (d) {
        today -> ""
        today.plusDays(1) -> "Завтра, "
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, ru)
            .replaceFirstChar { it.uppercase() } + ", "
    }
}
