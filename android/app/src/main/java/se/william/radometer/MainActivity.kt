package se.william.radometer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

private const val GREEN = 0xFF2BFF5B.toInt()
private const val GREEN_MID = 0xFF17A636.toInt()
private const val GREEN_DIM = 0xFF0D5F21.toInt()
private const val AMBER = 0xFFFFC21A.toInt()
private const val RED = 0xFFFF4230.toInt()
private const val BG = 0xFF04120A.toInt()

class MainActivity : Activity() {

    private val engine = GeigerEngine()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var cpsView: TextView
    private lateinit var doseView: TextView
    private lateinit var hintView: TextView
    private val presetButtons = mutableListOf<TextView>()

    private var lastPulseCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContentView(buildUi())
        refreshReadout(0f)
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    private fun label(text: String, size: Float, color: Int, spacing: Float = 0f) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = Typeface.MONOSPACE
            letterSpacing = spacing
            gravity = Gravity.CENTER
        }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(18f), dp(14f), dp(14f))
        }

        col.addView(label("RAD-O-METER", 11f, GREEN_MID, 0.5f))

        cpsView = label("0", 56f, GREEN).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(6f), 0, 0)
        }
        col.addView(cpsView)
        col.addView(label("PULSER / SEKUND", 11f, GREEN_MID, 0.3f))

        doseView = label("0.00 µSv/h", 14f, GREEN_MID).apply { setPadding(0, dp(8f), 0, 0) }
        col.addView(doseView)

        // --- ljudväljaren ---
        col.addView(label("▸ VÄLJ PULSLJUD", 9f, GREEN_DIM, 0.3f).apply {
            setPadding(0, dp(18f), 0, dp(8f))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PRESETS.forEachIndexed { i, p ->
            val b = TextView(this).apply {
                text = "${i + 1}. ${p.name}\n${p.desc}"
                textSize = 9f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(dp(2f), dp(9f), dp(2f), dp(9f))
                setOnClickListener { selectPreset(i) }
            }
            presetButtons += b
            row.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(5f)
            })
        }
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // --- reglaget, mitt på skärmen ---
        col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val slider = RadSlider(this)
        slider.onValueChanged = { v ->
            val r = GeigerEngine.positionToRate(v)
            engine.rate = r
            hintView.visibility = View.INVISIBLE
            refreshReadout(r)
        }
        col.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, dp(104f)))

        val scale = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        listOf("SÄKER", "BAKGRUND", "FÖRHÖJD", "FARA", "DÖDLIG").forEachIndexed { i, s ->
            scale.addView(
                label(s, 8f, GREEN_DIM, 0.2f).apply {
                    gravity = when (i) {
                        0 -> Gravity.START
                        4 -> Gravity.END
                        else -> Gravity.CENTER
                    }
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
        }
        col.addView(scale, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        hintView = label("▸ DRA REGLAGET", 11f, AMBER, 0.28f).apply {
            setPadding(0, dp(18f), 0, 0)
        }
        col.addView(hintView)

        // Tyngre vikt under än över, annars hamnar reglaget under skärmens mitt
        col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 2.6f))

        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ScanlineView(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        selectPreset(engine.currentPreset())
        return root
    }

    private fun selectPreset(index: Int) {
        engine.setPreset(index)
        presetButtons.forEachIndexed { i, b ->
            val on = i == index
            b.setTextColor(if (on) GREEN else GREEN_MID)
            b.background = GradientDrawable().apply {
                cornerRadius = dp(3f).toFloat()
                setColor(if (on) 0x242BFF5B else 0xFF061C0E.toInt())
                setStroke(dp(1f), if (on) GREEN else GREEN_DIM)
            }
        }
    }

    private fun refreshReadout(rate: Float) {
        cpsView.text = if (rate < 10f) String.format("%.1f", rate) else rate.roundToInt().toString()
        cpsView.setTextColor(if (rate > 300f) RED else if (rate > 40f) AMBER else GREEN)
        // SBM-20-tub: ungefär 151 pulser per minut motsvarar 1 µSv/h
        doseView.text = String.format("%.2f µSv/h", rate * 60f / 151f)
    }

    // -------------------------------------------------------------- livscykel

    override fun onResume() {
        super.onResume()
        engine.start()
        ui.post(blink)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(blink)
        engine.stop()          // inget ljud och ingen tråd i bakgrunden
    }

    /** Skärmen andas svagt i takt med pulserna. */
    private val blink = object : Runnable {
        override fun run() {
            val c = engine.pulseCount.get()
            if (c != lastPulseCount) {
                lastPulseCount = c
                cpsView.alpha = 1f
            } else if (cpsView.alpha > 0.82f) {
                cpsView.alpha = 0.82f
            }
            ui.postDelayed(this, 45)
        }
    }
}

/** Bildrörskänslan: tunna mörka linjer tvärs över hela skärmen. */
private class ScanlineView(context: android.content.Context) : View(context) {
    private val paint = Paint()

    init {
        val bmp = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.argb(0x57, 0, 0, 0))
        bmp.setPixel(0, 1, Color.TRANSPARENT)
        bmp.setPixel(0, 2, Color.TRANSPARENT)
        paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(paint)
    }
}
