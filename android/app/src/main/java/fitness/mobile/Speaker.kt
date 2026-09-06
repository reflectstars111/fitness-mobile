package fitness.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fitness.mobile.core.CoachEvent

class Speaker(context: Context, private val onFocusLoss: () -> Unit,
              private val onOutcome: (String, String, Long) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var current: CoachEvent? = null
    private var retryAfter = 0L
    private var closed = false
    val isReady: Boolean get() = ready && !closed
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change < 0) { stop(); onFocusLoss() }
        }, main).build()
    private lateinit var tts: TextToSpeech
    var status: String = "语音初始化中"
        private set
    init {
        tts = TextToSpeech(context) { code ->
            if (closed) return@TextToSpeech
            if (code == TextToSpeech.SUCCESS) {
                val voice = tts.voices?.firstOrNull { it.locale.language == "zh" && !it.isNetworkConnectionRequired }
                if (voice != null) {
                    tts.voice = voice; tts.setAudioAttributes(attributes)
                    ready = true; status = "离线中文语音就绪"
                } else status = "缺少离线中文语音，使用文字提示"
            } else status = "语音不可用，使用文字提示"
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post {
                current?.takeIf { it.id == id }?.let { event ->
                    val now = SystemClock.uptimeMillis()
                    if (now >= event.expiresAtMs) stop("expired") else onOutcome(event.id, "spoken", now)
                }
            } }
            override fun onDone(id: String?) { main.post { if (id != null && id == current?.id) {
                current = null; audio.abandonAudioFocusRequest(focus)
                onOutcome(id, "completed", SystemClock.uptimeMillis())
            } } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { if (id != null && id == current?.id) stop("error") } }
        })
    }
    fun say(event: CoachEvent, detailed: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!isReady || current != null || now < event.evidenceEndMs || now >= event.expiresAtMs || now < retryAfter) return false
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            retryAfter = now + 2000; return false
        }
        current = event
        if (tts.speak(if (detailed) event.detailedText else event.text, TextToSpeech.QUEUE_FLUSH, null, event.id) == TextToSpeech.ERROR) {
            stop("error"); return false
        }
        return true
    }
    /** No obsolete cue can survive lost visibility, resolved evidence or a higher-priority issue. */
    fun retain(events: List<CoachEvent>, observationUsable: Boolean) {
        val event = current ?: return
        if (events.any { it.priority > event.priority } ||
            (event.kind == CoachEvent.Kind.COUNT && !observationUsable) ||
            (event.kind != CoachEvent.Kind.COUNT && events.none { it.id == event.id })) stop("evidence_invalidated")
    }
    fun stop(reason: String = "cancelled") {
        val previous = current; current = null
        if (::tts.isInitialized) tts.stop()
        audio.abandonAudioFocusRequest(focus)
        if (previous != null) onOutcome(previous.id, reason, SystemClock.uptimeMillis())
    }
    override fun close() { closed = true; stop(); tts.shutdown() }
}
