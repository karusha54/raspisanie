package app.raspisanie

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private var date: LocalDate = LocalDate.now()
    private lateinit var dateView: TextView
    private lateinit var status: TextView
    private lateinit var scanBtn: Button
    private val names = ArrayList<EditText>()
    private val rooms = ArrayList<EditText>()
    private val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))
    private val reqPhoto = 7

    private fun prefs() = getSharedPreferences("schedule", MODE_PRIVATE)
    private fun currentClass(): String = prefs().getString("cls", "8Г") ?: "8Г"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.seed(this)

        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(24))
        }

        root.addView(TextView(this).apply {
            text = "Расписание уроков"
            textSize = 24f
            setPadding(0, 0, 0, px(12))
        })

        // --- класс + распознавание с фото ---
        val clsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clsRow.addView(TextView(this).apply {
            text = "Мой класс:"
            textSize = 16f
            setPadding(0, 0, px(12), 0)
        })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Recognizer.allClasses
        )
        spinner.setSelection(Recognizer.allClasses.indexOf(currentClass()).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs().edit().putString("cls", Recognizer.allClasses[pos]).apply()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        clsRow.addView(spinner)
        root.addView(clsRow)

        scanBtn = Button(this).apply {
            text = "Распознать с фото"
            setOnClickListener { pickPhoto() }
        }
        root.addView(scanBtn)
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, px(4), 0, px(12))
        }
        root.addView(status)

        // --- день ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹"
            setOnClickListener { shift(-1) }
        })
        dateView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }
        header.addView(dateView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "›"
            setOnClickListener { shift(1) }
        })
        root.addView(header)

        for (i in 0 until 6) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(px(28), ViewGroup.LayoutParams.WRAP_CONTENT))

            val name = EditText(this).apply {
                hint = "Предмет"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val room = EditText(this).apply {
                hint = "Каб."
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
            }
            names.add(name)
            rooms.add(room)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            row.addView(room, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }

        root.addView(TextView(this).apply {
            text = "Окно (нет урока) - оставь строку пустой. Звонки подставляются по дню недели."
            textSize = 13f
            setPadding(0, px(12), 0, px(12))
        })

        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener {
                save()
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        load()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun shift(days: Long) {
        save()
        date = date.plusDays(days)
        load()
    }

    private fun load() {
        dateView.text = date.format(fmt)
        val rows = Store.load(this, date)
        for (i in 0 until 6) {
            val r = rows.getOrNull(i)
            names[i].setText(r?.first ?: "")
            rooms[i].setText(r?.second ?: "")
        }
    }

    private fun save() {
        val rows = names.indices.map {
            names[it].text.toString().trim() to rooms[it].text.toString().trim()
        }
        Store.save(this, date, rows)
        ScheduleWidget.refresh(this)
    }

    // ---------- фото ----------

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Фото расписания"), reqPhoto)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPhoto || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val cls = currentClass()
        status.text = "Распознаю $cls... это может занять до минуты"
        scanBtn.isEnabled = false
        Thread {
            var result: Recognizer.Result? = null
            var err: String? = null
            try {
                result = Recognizer.recognize(this, decode(uri), cls)
            } catch (e: Throwable) {
                err = e.message ?: e.toString()
            }
            runOnUiThread {
                scanBtn.isEnabled = true
                if (err != null) status.text = "Ошибка: $err" else showResult(result, cls)
            }
        }.start()
    }

    private fun decode(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val m = maxOf(w, h)
            if (m > 2200) {
                val k = 2200f / m
                dec.setTargetSize((w * k).toInt(), (h * k).toInt())
            }
        }
    }

    private fun showResult(res: Recognizer.Result?, cls: String) {
        if (res == null) {
            status.text = "Не нашёл колонку $cls. Сними таблицу целиком и ровно, чтобы был виден заголовок класса."
            return
        }
        save()
        res.date?.let { date = it }
        load()
        for (i in 0 until 6) {
            val r = res.rows.getOrNull(i)
            names[i].setText(r?.first ?: "")
            rooms[i].setText(r?.second ?: "")
        }
        val dateNote = if (res.date != null) "" else " (дату на фото не нашёл, заполнил выбранный день)"
        status.text = "Распознано уроков: ${res.count}$dateNote. Проверь список и нажми «Сохранить»."
    }
}
