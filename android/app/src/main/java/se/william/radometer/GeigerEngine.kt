package se.william.radometer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * En pulskaraktär.
 *
 * Alla fem bygger på samma modell — en impuls exciterar tre resonatorer som
 * klingar av. Det som skiljer dem åt är hur apparaten är byggd: hur stor lådan
 * är, om högtalaren sitter i en bakelitburk eller ett öronskal, hur hårt
 * slutsteget drivs.
 *
 * kropp = grundklangen, bett = den metalliska mitten, snärt = transienten.
 * Varje resonator anges som frekvens (Hz), Q (hur länge den ringer) och nivå.
 */
data class Preset(
    val name: String,
    val desc: String,
    val body: Triple<Float, Float, Float>,
    val bite: Triple<Float, Float, Float>,
    val snap: Triple<Float, Float, Float>,
    val decay: Float,      // envelopens tidskonstant, sekunder
    val noiseMs: Float,    // brusknäppet i excitationen
    val noiseAmt: Float,
    val sat: Float,        // hur hårt högtalaren mättas
    val lenMs: Float,
    val trim: Float        // nivåutjämning mellan karaktärerna
)

val PRESETS = listOf(
    Preset("TORRT", "liten burk",
        Triple(1000f, 9f, 0.75f), Triple(2700f, 14f, 1.00f), Triple(5900f, 4f, 0.40f),
        0.0022f, 0.40f, 0.8f, 2.2f, 18f, 1.00f),
    Preset("DOSIMETER", "sovjetisk",
        Triple(780f, 12f, 1.00f), Triple(1900f, 18f, 0.90f), Triple(3600f, 7f, 0.35f),
        0.0060f, 0.80f, 0.7f, 3.0f, 30f, 0.92f),
    Preset("HÖRLUR", "öronsnäcka",
        Triple(2200f, 6f, 0.30f), Triple(4200f, 9f, 1.00f), Triple(7800f, 5f, 0.60f),
        0.0012f, 0.25f, 1.0f, 1.5f, 10f, 1.05f),
    Preset("PIEZO", "digital",
        Triple(3000f, 30f, 0.60f), Triple(4100f, 40f, 1.00f), Triple(8200f, 6f, 0.25f),
        0.0080f, 0.20f, 0.4f, 1.2f, 25f, 0.88f),
    Preset("SKRAMMEL", "grov",
        Triple(600f, 7f, 1.00f), Triple(1500f, 9f, 0.80f), Triple(3000f, 5f, 0.60f),
        0.0040f, 2.50f, 1.0f, 4.0f, 22f, 0.95f)
)

/**
 * Ljudmotorn.
 *
 * Tidpunkterna är det viktiga: radioaktivt sönderfall är en poissonprocess, så
 * för varje block drar vi antalet pulser ur en poissonfördelning och placerar
 * dem på slumpmässiga sampelpositioner. Det är därför en riktig apparat låter
 * oregelbunden i stället för som en metronom.
 */
class GeigerEngine {

    companion object {
        const val SAMPLE_RATE = 48000
        private const val BLOCK = 480            // 10 ms per mixomgång
        private const val VARIANTS = 24          // olika pulser, ger variation
        private const val MAX_PER_BLOCK = 400    // tak, skyddar mot överbelastning
        private const val MAX_CLICK = 1440       // 30 ms — den längsta karaktären

        const val MIN_RATE = 0.4f                // bakgrundsstrålning
        const val MAX_RATE = 1400f               // dödlig dos

        /** Reglagets läge (0..1) till pulser per sekund, exponentiellt. */
        fun positionToRate(p: Float): Float =
            if (p <= 0.001f) 0f
            else MIN_RATE * (MAX_RATE / MIN_RATE).toDouble().pow(p.toDouble()).toFloat()
    }

    /** Antal pulser sedan start — UI:t använder det för blinket. */
    val pulseCount = AtomicInteger(0)

    @Volatile var rate: Float = 0f
    @Volatile private var clicks: Array<FloatArray> = renderBank(PRESETS[1])
    @Volatile private var presetIndex: Int = 1

    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private val rnd = Random(System.nanoTime())

    fun setPreset(index: Int) {
        if (index == presetIndex) return
        presetIndex = index
        clicks = renderBank(PRESETS[index])      // byts atomärt, mixern ser den nya
    }

    fun currentPreset() = presetIndex

    // ---------------------------------------------------------------- syntes

    /** Bandpassfilter (RBJ-biquad) kört över hela excitationen. */
    private fun bandpass(input: FloatArray, f0: Float, q: Float): FloatArray {
        val w0 = 2.0 * PI * f0 / SAMPLE_RATE
        val alpha = sin(w0) / (2.0 * q)
        val b0 = alpha
        val b2 = -alpha
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cos(w0)
        val a2 = 1.0 - alpha
        val out = FloatArray(input.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (n in input.indices) {
            val x0 = input[n].toDouble()
            val y0 = (b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            out[n] = y0.toFloat()
        }
        return out
    }

    private fun renderBank(p: Preset): Array<FloatArray> = Array(VARIANTS) { renderClick(p, it) }

    private fun renderClick(p: Preset, index: Int): FloatArray {
        val r = Random(1000L + index)            // deterministiskt per variant
        val n = min(MAX_CLICK, (p.lenMs / 1000f * SAMPLE_RATE).toInt())

        // Excitation: spänningsspiken plus ett kort brusknäpp
        val exc = FloatArray(n)
        exc[0] = 1f
        val noiseLen = max(1, (p.noiseMs / 1000f * SAMPLE_RATE).toInt())
        for (i in 0 until min(noiseLen, n)) {
            exc[i] += (r.nextFloat() * 2f - 1f) * (1f - i.toFloat() / noiseLen) * p.noiseAmt
        }

        val j = index.toFloat() / VARIANTS       // variation puls till puls
        fun reson(spec: Triple<Float, Float, Float>, spread: Float) =
            bandpass(exc, spec.first * (1f + j * 0.10f) + r.nextFloat() * spread, spec.second)

        val body = reson(p.body, 120f)
        val bite = reson(p.bite, 260f)
        val snap = reson(p.snap, 500f)

        val out = FloatArray(n)
        var peak = 1e-9f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val env = exp(-t / p.decay)
            var v = (body[i] * p.body.third + bite[i] * p.bite.third + snap[i] * p.snap.third) * env
            v = tanh(v * p.sat) / p.sat          // högtalarmättnad
            out[i] = v
            if (abs(v) > peak) peak = abs(v)
        }
        // Normalisera, och tona ut slutet så bufferten landar i noll
        val fade = (0.0015f * SAMPLE_RATE).toInt()
        for (i in 0 until n) {
            var g = p.trim / peak
            if (i > n - fade) g *= (n - i).toFloat() / fade
            out[i] *= g
        }
        return out
    }

    // ------------------------------------------------------------ sönderfall

    /** Antal händelser i ett tidsfönster med väntevärde [lambda]. */
    private fun poisson(lambda: Float): Int {
        if (lambda <= 0f) return 0
        if (lambda < 30f) {                                     // Knuths metod
            val l = exp(-lambda.toDouble())
            var k = 0
            var p = 1.0
            do { k++; p *= rnd.nextDouble() } while (p > l)
            return k - 1
        }
        val u1 = rnd.nextDouble(); val u2 = rnd.nextDouble()     // normalapprox
        val z = sqrt(-2.0 * ln(1.0 - u1)) * cos(2.0 * PI * u2)
        return max(0, (lambda + sqrt(lambda) * z.toFloat()).toInt())
    }

    // ----------------------------------------------------------- uppspelning

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, BLOCK * 2 * 4)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val t = try { builder.build() } catch (e: Exception) { return }
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return }
        t.play()
        track = t
        running = true
        worker = thread(name = "geiger-mixer", priority = Thread.MAX_PRIORITY) { mixLoop(t) }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    private fun mixLoop(t: AudioTrack) {
        // Ackumulatorn är längre än blocket: en puls som börjar sent i ett block
        // måste kunna klinga vidare in i nästa.
        val acc = FloatArray(BLOCK + MAX_CLICK)
        val out = ShortArray(BLOCK)
        val satNorm = tanh(1.7f)
        var hissLp = 0f
        var hissA = 0f
        var hissB = 0f

        while (running) {
            val r = rate
            val bank = clicks

            // --- pulser i det här blocket ---
            if (r > 0f) {
                val lambda = r * BLOCK / SAMPLE_RATE
                val n = min(poisson(lambda), MAX_PER_BLOCK)
                // Tätare pulser → lägre nivå per puls, annars spårar summan ur
                val gs = 1f / sqrt(max(1f, r / 60f))
                for (k in 0 until n) {
                    val off = rnd.nextInt(BLOCK)
                    val c = bank[rnd.nextInt(bank.size)]
                    val g = (0.6f + rnd.nextFloat() * 0.4f) * gs
                    for (i in c.indices) acc[off + i] += c[i] * g
                }
                if (n > 0) pulseCount.addAndGet(n)
            }

            // --- detektorbrus: limmar ihop pulserna när de smälter samman ---
            val hiss = if (r < 150f) 0f else min(0.055f, (r - 150f) / 1250f * 0.055f)
            if (hiss > 0f) {
                for (i in 0 until BLOCK) {
                    val w = rnd.nextFloat() * 2f - 1f
                    hissLp += (w - hissLp) * 0.35f
                    hissA += (hissLp - hissA) * 0.25f          // enkel resonans
                    hissB += (hissA - hissB) * 0.08f
                    acc[i] += (hissA - hissB) * hiss * 3f
                }
            }

            // --- ut ---
            for (i in 0 until BLOCK) {
                var v = tanh(acc[i] * 1.7f) / satNorm * 0.9f
                if (v > 1f) v = 1f else if (v < -1f) v = -1f
                out[i] = (v * 32000f).toInt().toShort()
            }

            // Skjut ner svansen och nolla det som frigjorts
            System.arraycopy(acc, BLOCK, acc, 0, acc.size - BLOCK)
            java.util.Arrays.fill(acc, acc.size - BLOCK, acc.size, 0f)

            t.write(out, 0, BLOCK)   // blockerar tills ljudkortet vill ha mer
        }
    }
}
