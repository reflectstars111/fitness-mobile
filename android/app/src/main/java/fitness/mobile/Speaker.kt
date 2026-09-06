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
import fitness.mobile.core.LiveCounter

class Speaker(context: Context, private val onFocusLoss: () -> Unit,
              private val onSpoken: (Long, Long) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var lastSpoken = -10000L
    private var expiry = 0L
    private var utterance: String? = null
    private var evidenceEnd = 0L
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
                if (id == utterance) {
                    val now = SystemClock.uptimeMillis()
                    if (now > expiry) stop() else onSpoken(evidenceEnd, now)
                }
            } }
            override fun onDone(id: String?) { main.post { if (id == utterance) {
                utterance = null; audio.abandonAudioFocusRequest(focus)
            } } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { if (id == utterance) stop() } }
        })
    }
    fun say(event: LiveCounter.Event) {
        val now = SystemClock.uptimeMillis()
        if (!ready || now > event.expiresAtMs || now - lastSpoken < 2000) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        lastSpoken = now; expiry = event.expiresAtMs
        evidenceEnd = event.evidenceEndMs; utterance = "rep-${event.evidenceEndMs}"
        if (tts.speak("${event.count}", TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.ERROR) stop()
    }
    fun stop() { utterance = null; if (::tts.isInitialized) tts.stop(); audio.abandonAudioFocusRequest(focus) }
    override fun close() { stop(); tts.shutdown() }
}
