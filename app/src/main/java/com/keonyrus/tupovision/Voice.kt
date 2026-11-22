package com.keonyrus.tupovision

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speech recognition for one utterance at a time. All calls and callbacks are on the main thread,
 * which is what SpeechRecognizer requires.
 */
class Stt(private val context: Context, private val callbacks: Callbacks) {
  interface Callbacks {
    fun onSpeechStart()

    fun onPartial(text: String)

    fun onFinal(text: String)

    fun onError(code: Int)
  }

  private var recognizer: SpeechRecognizer? = null
  // False once the current utterance has been delivered or cancelled, so late callbacks are dropped.
  private var active = false

  fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

  fun start(languageTag: String) {
    val current =
        recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also {
              it.setRecognitionListener(listener)
              recognizer = it
            }
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
          // A hint only; recognizers are free to ignore it.
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }
    active = true
    current.startListening(intent)
  }

  fun cancel() {
    active = false
    recognizer?.cancel()
  }

  fun destroy() {
    active = false
    recognizer?.destroy()
    recognizer = null
  }

  private fun firstResult(bundle: Bundle?): String =
      bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

  private val listener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
          if (active) callbacks.onSpeechStart()
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
          if (!active) return
          active = false
          callbacks.onError(error)
        }

        override fun onResults(results: Bundle?) {
          if (!active) return
          active = false
          callbacks.onFinal(firstResult(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
          if (active) callbacks.onPartial(firstResult(partialResults))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      }
}

/** The phone's text-to-speech engine, fed sentence by sentence as the answer streams in. */
class Tts(context: Context, private val onReady: (Boolean) -> Unit) {
  @Volatile
  var ready = false
    private set

  private var counter = 0
  private val engine: TextToSpeech

  init {
    engine =
        TextToSpeech(context.applicationContext) { status ->
          ready = status == TextToSpeech.SUCCESS
          onReady(ready)
        }
  }

  /** Returns false when the engine has no voice for this language. */
  fun configure(languageTag: String, throughCallAudio: Boolean): Boolean {
    val usage =
        if (throughCallAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION
        else AudioAttributes.USAGE_ASSISTANT
    engine.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    engine.setSpeechRate(1.1f)
    val result = engine.setLanguage(Locale.forLanguageTag(languageTag))
    return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
  }

  fun speak(text: String) {
    counter += 1
    engine.speak(text, TextToSpeech.QUEUE_ADD, null, "utterance-$counter")
  }

  val isSpeaking: Boolean
    get() = engine.isSpeaking

  fun stop() {
    engine.stop()
  }

  fun shutdown() {
    engine.stop()
    engine.shutdown()
  }
}

/**
 * Sends call-style audio to the glasses: their microphone only works over the Bluetooth hands-free
 * link, and while that link is up the answer plays through it too.
 */
object AudioRouter {
  private const val ROUTE_TIMEOUT_MS = 3000L
  private const val KEEP_ALIVE_RATE = 16000
  private var routed = false
  private var previousMode = AudioManager.MODE_NORMAL
  // Identity of the running keep-alive loop; a loop stops when it is no longer the current one.
  @Volatile private var keepAliveToken: Any? = null

  private var onHeadset = false

  val isRouted: Boolean
    get() = routed

  /**
   * Call-style audio for a live two-way conversation. Uses the glasses when [preferHeadset] is set
   * and they are connected, otherwise the phone's own microphone and loudspeaker. Call mode is
   * what switches on Android's echo cancellation, so it is set in both cases. Returns true when
   * the glasses carry the audio.
   */
  suspend fun enableCall(context: Context, preferHeadset: Boolean): Boolean {
    if (preferHeadset && enable(context)) return true
    if (routed) disable(context)
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    previousMode = audio.mode
    routed = true
    runCatching {
      audio.mode = AudioManager.MODE_IN_COMMUNICATION
      // Without this, call mode plays through the quiet earpiece.
      audio.availableCommunicationDevices
          .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
          ?.let { audio.setCommunicationDevice(it) }
    }
    startKeepAlive()
    return false
  }

  /** The glasses microphone as a recording device, or null when audio is on the phone. */
  fun inputDevice(context: Context): AudioDeviceInfo? {
    if (!onHeadset) return null
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return runCatching {
          audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
          }
        }
        .getOrNull()
  }

  /** Returns true when microphone and speaker are now on a Bluetooth headset. */
  suspend fun enable(context: Context): Boolean {
    if (routed) return true
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val headset =
        runCatching {
              audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
              }
            }
            .getOrNull() ?: return false
    previousMode = audio.mode
    routed = true
    val accepted =
        runCatching {
              audio.mode = AudioManager.MODE_IN_COMMUNICATION
              audio.setCommunicationDevice(headset)
            }
            .getOrDefault(false)
    startKeepAlive()
    val connected =
        accepted &&
            withTimeoutOrNull(ROUTE_TIMEOUT_MS) {
              while (audio.communicationDevice?.type != headset.type) delay(100)
              true
            } == true
    if (!connected) disable(context)
    onHeadset = connected
    return connected
  }

  fun disable(context: Context) {
    if (!routed) return
    keepAliveToken = null
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    runCatching { audio.clearCommunicationDevice() }
    runCatching { audio.mode = previousMode }
    routed = false
    onHeadset = false
  }

  /**
   * Android drops an app's call-audio route a few seconds after the app stops playing or recording
   * call audio itself. Recognition and speech run in system services, not in this process, so a
   * silent call-audio track keeps the route to the glasses open between sentences.
   */
  private fun startKeepAlive() {
    if (keepAliveToken != null) return
    val minSize =
        AudioTrack.getMinBufferSize(
            KEEP_ALIVE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    if (minSize <= 0) return
    val track =
        try {
          AudioTrack.Builder()
              .setAudioAttributes(
                  AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build(),
              )
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setSampleRate(KEEP_ALIVE_RATE)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build(),
              )
              .setBufferSizeInBytes(minSize * 2)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build()
        } catch (e: Exception) {
          return
        }
    if (track.state != AudioTrack.STATE_INITIALIZED) {
      track.release()
      return
    }
    val token = Any()
    keepAliveToken = token
    Thread(
            {
              // 20 ms of silence per write; the blocking write paces the loop.
              val silence = ByteArray(KEEP_ALIVE_RATE / 50 * 2)
              try {
                track.play()
                while (keepAliveToken === token) {
                  if (track.write(silence, 0, silence.size) < 0) break
                }
              } catch (e: Exception) {
                // The track died with the route; the next enable() starts a new one.
              } finally {
                if (keepAliveToken === token) keepAliveToken = null
                runCatching { track.stop() }
                track.release()
              }
            },
            "call-audio-keepalive",
        )
        .apply { isDaemon = true }
        .start()
  }
}

/** Cuts a stream of text into sentences so speech can start before the answer is complete. */
class SentenceChunker {
  private val buffer = StringBuilder()

  fun push(delta: String): List<String> {
    buffer.append(delta)
    val sentences = ArrayList<String>()
    while (true) {
      val cut = boundary()
      if (cut < 0) break
      val sentence = buffer.substring(0, cut).trim()
      buffer.delete(0, cut)
      if (sentence.isNotEmpty()) sentences.add(sentence)
    }
    return sentences
  }

  fun flush(): String? {
    val rest = buffer.toString().trim()
    buffer.setLength(0)
    return rest.ifEmpty { null }
  }

  /** Index just past the first sentence end, or -1. "3.5" and very short fragments do not split. */
  private fun boundary(): Int {
    for (i in buffer.indices) {
      val ch = buffer[i]
      val end =
          ch == '\n' ||
              ((ch == '.' || ch == '!' || ch == '?' || ch == '…') &&
                  i + 1 < buffer.length &&
                  buffer[i + 1].isWhitespace())
      if (end && i + 1 >= MIN_SENTENCE) return i + 1
    }
    return -1
  }

  private companion object {
    const val MIN_SENTENCE = 12
  }
}
