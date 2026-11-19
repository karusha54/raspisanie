package app.raspisanie

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Распознавание фото таблицы "Расписание уроков на <дата>" (все классы сразу).
 * Находим заголовок нужного класса, берём колонку под ним и вытаскиваем предметы и кабинеты.
 */
object Recognizer {

    class Word(val text: String, val l: Int, val t: Int, val w: Int, val h: Int) {
        val cx: Double get() = l + w / 2.0
        val cy: Double get() = t + h / 2.0
    }

    class Result(val date: LocalDate?, val rows: List<Pair<String, String>>, val count: Int)

    private val order = listOf(
        listOf("5А", "5Б", "5В", "5Г", "6А", "6Б"),
        listOf("6В", "6Г", "6Д", "7А", "7Б", "7В"),
        listOf("7Г", "8А", "8Б", "8В", "8Г", "8Д"),
        listOf("9А", "9Б", "9В", "9Г", "10", "11")
    )
    val allClasses: List<String> = order.flatten()

    /** Сокращения из таблицы -> нормальные названия. Дополняй, если встретятся новые. */
    private val dict = mapOf(
        "ин.яз" to "Ин. язык", "гос.язык" to "Гос. язык", "рус.яз" to "Рус. язык",
        "род.яз" to "Род. язык", "род.лит" to "Род. литература", "литер" to "Литература",
        "матем" to "Математика", "алгебра" to "Алгебра", "геометр" to "Геометрия",
        "физика" to "Физика", "химия" to "Химия", "биология" to "Биология",
        "географ" to "География", "геогр" to "География", "история" to "История",
        "общест" to "Обществознание", "общество" to "Общество", "информ" to "Информатика",
        "физ.культ" to "Физкультура", "технолог" to "Технология", "музыка" to "Музыка",
        "изо" to "ИЗО", "обзр" to "ОБЗР", "вис" to "ВИС", "ип" to "ИП", "право" to "Право",
        "фин.грам" to "Фин. грамотность"
    )

    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    )

    // ---------- OCR ----------

    private fun ocr(ctx: Context, bmp: Bitmap): List<Word> {
        val dir = File(ctx.filesDir, "tessdata")
        dir.mkdirs()
        val f = File(dir, "rus.traineddata")
        if (!f.exists()) {
            ctx.assets.open("rus.traineddata").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        val tess = TessBaseAPI()
        if (!tess.init(ctx.filesDir.absolutePath, "rus")) {
            tess.recycle()
            return emptyList()
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        tess.setImage(bmp)
        tess.getUTF8Text()
        val out = ArrayList<Word>()
        val iter = tess.getResultIterator()
        if (iter != null) {
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            iter.begin()
            do {
                val txt = iter.getUTF8Text(level)?.trim() ?: ""
                if (txt.isNotEmpty()) {
                    val r = iter.getBoundingRect(level)
                    out.add(Word(txt, r.left, r.top, r.width(), r.height()))
                }
            } while (iter.next(level))
            iter.delete()
        }
        tess.recycle()
        return out
    }

    fun recognize(ctx: Context, bmp: Bitmap, cls: String): Result? {
        val ws = ocr(ctx, bmp)
        return analyze(ws, cls, bmp.width, bmp.height)
    }

    // ---------- разбор ----------

    private fun normCls(s: String): String {
        val map = mapOf(
            'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М', 'H' to 'Н',
            'O' to 'О', 'P' to 'Р', 'C' to 'С', 'T' to 'Т', 'X' to 'Х'
        )
        return s.replace(" ", "").uppercase().map { map[it] ?: it }.joinToString("")
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun matchSubject(tok: String): String? {
        val k = tok.lowercase().filter { it in 'а'..'я' || it in 'a'..'z' || it == '.' || it == 'ё' }
        if (k.count { it != '.' } < 2) return null
        var best: String? = null
        var bs = 0.0
        for ((key, v) in dict) {
            val s = when {
                key == k -> 1.0
                key.length <= 3 -> 0.0
                else -> 1.0 - lev(k, key).toDouble() / maxOf(k.length, key.length)
            }
            if (s > bs) {
                bs = s
                best = v
            }
        }
        return if (bs >= 0.62) best else null
    }

    private fun fixSeg(s: String): String {
        var x = s.map {
            when (it) {
                'з', 'З' -> '3'
                'о', 'О', 'O', 'o' -> '0'
                else -> it
            }
        }.joinToString("")
        if (Regex("\\d\\d[вВ]").matches(x)) x = x.dropLast(1) + "8"
        if (Regex("\\d{3}6").matches(x)) x = x.dropLast(1) + "б"
        return x
    }

    private fun roomOf(raw: String): String? {
        val t = raw.replace("|", "").replace(" ", "")
        if (t.isEmpty()) return null
        val parts = t.split("/").map { fixSeg(it) }
        val nonEmpty = parts.filter { it.isNotEmpty() }
        val re = Regex("\\d{2,3}[а-яa-z]?")
        if (nonEmpty.isEmpty() || nonEmpty.any { !re.matches(it) }) return null
        return parts.joinToString("/")
    }

    private class Subj(val name: String, val w: Word) {
        var room: String = ""
    }

    private fun findDate(ws: List<Word>, h: Int): LocalDate? {
        val text = ws.filter { it.t < 0.06 * h }.sortedBy { it.l }
            .joinToString(" ") { it.text }.lowercase()
        val m = Regex("(\\d{1,2})\\s*(" + months.joinToString("|") + ")").find(text) ?: return null
        val day = m.groupValues[1].toInt()
        val mon = months.indexOf(m.groupValues[2]) + 1
        return try {
            val today = LocalDate.now()
            var best = LocalDate.of(today.year, mon, day)
            for (y in listOf(today.year - 1, today.year + 1)) {
                val c = LocalDate.of(y, mon, day)
                if (abs(ChronoUnit.DAYS.between(c, today)) < abs(ChronoUnit.DAYS.between(best, today))) best = c
            }
            best
        } catch (e: DateTimeException) {
            null
        }
    }

    fun analyze(ws: List<Word>, cls: String, width: Int, height: Int): Result? {
        val heads = ws.filter { it.h >= 0.007 * height && normCls(it.text) in allClasses }
        val hd = heads.filter { normCls(it.text) == cls }.maxByOrNull { it.h } ?: return null
        val blk = order.first { cls in it }
        val ti = blk.indexOf(cls)

        val same = HashMap<Int, Word>()
        for (w in heads) {
            val i = blk.indexOf(normCls(w.text))
            if (i >= 0 && abs(w.t - hd.t) < 0.023 * height) same[i] = w
        }
        same[ti] = hd
        val keys = same.keys.sorted()
        val sps = ArrayList<Double>()
        for (k in 0 until keys.size - 1) {
            val a = same[keys[k]]!!
            val b = same[keys[k + 1]]!!
            sps.add((b.cx - a.cx) / (keys[k + 1] - keys[k]))
        }
        sps.sort()
        val sp = if (sps.isEmpty()) 0.15 * width else sps[sps.size / 2]
        val left = same[ti - 1]?.let { (hd.cx + it.cx) / 2 } ?: (hd.cx - sp / 2)
        val right = same[ti + 1]?.let { (hd.cx + it.cx) / 2 } ?: (hd.cx + sp / 2)

        val top = (hd.t + hd.h).toDouble()
        val below = heads.filter { it.t > hd.t + 0.078 * height }.map { it.t }
        val bot = (below.minOrNull() ?: (height + 5)) - 5.0

        val col = ws.filter {
            it.t >= top && it.t < bot && it.cx >= left && it.cx <= right &&
                it.text.any { c -> c.isLetterOrDigit() }
        }.sortedWith(compareBy<Word>({ it.cy }, { it.l }))

        val subj = ArrayList<Subj>()
        val rooms = ArrayList<Pair<String, Word>>()
        for (w in col) {
            val letters = w.text.count { it.isLetter() }
            val hasDigit = w.text.any { it.isDigit() }
            val m = matchSubject(w.text)
            if (m != null && (!hasDigit || letters >= 3)) {
                subj.add(Subj(m, w))
            } else if (hasDigit) {
                val r = roomOf(w.text)
                if (r != null) rooms.add(Pair(r, w))
            }
        }

        // кабинет относим к ближайшему по вертикали предмету (в той же строке или чуть ниже)
        for ((r, rw) in rooms) {
            var best: Subj? = null
            var bd = Double.MAX_VALUE
            for (s in subj) {
                val h = s.w.h
                val dy = rw.cy - s.w.cy
                if (dy >= -0.7 * h && dy <= 2.2 * h && abs(dy) < bd) {
                    bd = abs(dy)
                    best = s
                }
            }
            if (best != null && best.room.isEmpty()) best.room = r
        }

        // пропуски (окна) по расстояниям между предметами
        val res = ArrayList<Pair<String, String>>()
        if (subj.isNotEmpty()) {
            val cys = subj.map { it.w.cy }
            var p = 45.0
            if (cys.size > 1) {
                val ds = (1 until cys.size).map { cys[it] - cys[it - 1] }.sorted()
                p = ds[ds.size / 2]
            }
            val first = if (p > 0) (cys[0] - top) / p else 1.0
            if (first > 1.7) repeat(Math.round(first).toInt() - 1) { res.add(Pair("", "")) }
            for (i in subj.indices) {
                if (i > 0 && cys[i] - cys[i - 1] > 1.75 * p) {
                    repeat(Math.round((cys[i] - cys[i - 1]) / p).toInt() - 1) { res.add(Pair("", "")) }
                }
                res.add(Pair(subj[i].name, subj[i].room))
            }
        }
        val rows = res.take(6)
        return Result(findDate(ws, height), rows, rows.count { it.first.isNotBlank() })
    }
}
