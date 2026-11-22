package com.keonyrus.tupovision

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_MODEL = "claude-haiku-5-5"

/** Haiku: fast and cheap. Sonnet: sharper, about three times the price. Opus: the best, costly. */
val FALLBACK_MODELS: List<String> =
    listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")

const val DEFAULT_MONTHLY_LIMIT = 300
const val DEFAULT_IDLE_SECONDS = 45

enum class Phase(val label: String) {
  IDLE("Arrêté"),
  CONNECTING("Connexion"),
  LISTENING("J'écoute"),
  THINKING("Je réfléchis"),
  SPEAKING("Je réponds"),
  PAUSED("En pause"),
}

data class UiState(
    val metaInstalled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val registration: String = "—",
    val registered: Boolean = false,
    val deviceCount: Int = 0,
    val hasActiveDevice: Boolean = false,
    val signedIn: Boolean = false,
    val loginInProgress: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val sessionState: String = "—",
    val streamState: String = "—",
    val frameInfo: String = "—",
    val partial: String = "",
    val question: String = "",
    val answer: String = "",
    val latencyMs: Long? = null,
    val frame: Bitmap? = null,
    val error: String? = null,
    val model: String = DEFAULT_MODEL,
    val models: List<String> = FALLBACK_MODELS,
    val glassesMic: Boolean = true,
    val photoMode: Boolean = false,
    val wakeWord: String = "",
    val language: String = "en-US",
    /** True: live voice through Gemini Live. False: ChatGPT plan with on-phone speech. */
    val liveMode: Boolean = false,
    val geminiKeySet: Boolean = false,
    val bargeIn: Boolean = true,
    /** A gesture on the glasses starts and stops the conversation. Not kept across restarts. */
    val standby: Boolean = false,
    val standbyAudio: String = "",
    /** Whether the user has granted the access needed to watch other apps' media sessions. */
    val mediaAccess: Boolean = false,
    val triggerPackage: String = MediaWatch.DEFAULT_TRIGGER,
    val log: List<String> = emptyList(),
    /** Questions allowed per calendar month; 0 means no limit. */
    val monthlyLimit: Int = DEFAULT_MONTHLY_LIMIT,
    /** The conversation stops after this many seconds without a question; 0 means never. */
    val idleSeconds: Int = DEFAULT_IDLE_SECONDS,
    val monthQuestions: Int = 0,
    val monthInputTokens: Long = 0,
    val monthOutputTokens: Long = 0,
)

/**
 * The whole assistant: glasses session and camera, the listen → ask → speak loop, and the state the
 * screen shows. It outlives the activity so the conversation continues with the screen off.
 * Everything here runs on the main thread unless a block says otherwise.
 */
object Engine {
  private const val TAG = "LunettesClaude"
  private const val PREFS = "settings"
  private const val META_AI_PACKAGE = "com.facebook.stella"
  private const val CLAUDE_KEY = "claude_api_key"
  // Spoken answers are short; this also caps what one question can cost.
  private const val MAX_ANSWER_TOKENS = 500
  private const val MAX_HISTORY = 6
  private const val MAX_LOG = 200
  private const val PHOTO_TIMEOUT_MS = 3000L
  private const val STREAM_WAIT_MS = 10_000L
  private const val GEMINI_KEY = "gemini_api_key"
  // Lighter than the per-question frame: one of these goes up every second.
  private const val LIVE_JPEG_QUALITY = 65

  val REQUIRED_PERMISSIONS: Array<String> =
      arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)

  private lateinit var app: Application
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private val logLines = ArrayDeque<String>()
  private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

  // Glasses
  private var wearablesReady = false
  private val selector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private val sessionJobs = ArrayList<Job>()
  private var sessionStarted = false
  private var streaming = false
  private var serviceStarted = false

  // Voice
  private var stt: Stt? = null
  private var tts: Tts? = null
  private var voiceActive = false
  private var turnJob: Job? = null
  private var errorStreak = 0
  private var lastActivityAt = 0L
  private val history = ArrayDeque<Turn>()

  // Live voice
  private var live: GeminiLive? = null
  private var liveHandle: String? = null
  // 0: full setup resuming the last session, 1: full setup from scratch, 2: bare minimum.
  private var liveAttempt = 0
  private var liveDrops = 0
  private var liveTurnDone = true

  // ---- setup ----

  fun attach(application: Application) {
    app = application
    val prefs = prefs()
    update {
      it.copy(
          signedIn = SecureStore.get(app, CLAUDE_KEY) != null,
          model =
              (prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).let {
                if (it in FALLBACK_MODELS) it else DEFAULT_MODEL
              },
          monthlyLimit = prefs.getInt("monthly_limit", DEFAULT_MONTHLY_LIMIT),
          idleSeconds = prefs.getInt("idle_seconds", DEFAULT_IDLE_SECONDS),
          glassesMic = prefs.getBoolean("glasses_mic", true),
          photoMode = prefs.getBoolean("photo_mode", false),
          wakeWord = prefs.getString("wake_word", "") ?: "",
          language = prefs.getString("language", null) ?: "fr-FR",
          geminiKeySet = SecureStore.get(app, GEMINI_KEY) != null,
          liveMode = prefs.getBoolean("live_mode", false),
          bargeIn = prefs.getBoolean("barge_in", true),
          triggerPackage =
              prefs.getString("trigger_package", null) ?: MediaWatch.DEFAULT_TRIGGER,
      )
    }
    refreshEnvironment()
    refreshUsage()
  }

  /** Re-reads what can change while the app is in the background: installs and permissions. */
  fun refreshEnvironment() {
    val metaInstalled =
        runCatching { app.packageManager.getPackageInfo(META_AI_PACKAGE, 0) }.isSuccess
    val granted =
        REQUIRED_PERMISSIONS.all {
          app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    update {
      it.copy(
          metaInstalled = metaInstalled,
          permissionsGranted = granted,
          mediaAccess = MediaWatch.hasAccess(app),
      )
    }
    if (granted) ensureWearables()
  }

  /** The SDK may only be touched after Bluetooth permission is granted. */
  private fun ensureWearables() {
    if (wearablesReady) return
    wearablesReady = true
    Wearables.initialize(app).onFailure { error, _ ->
      log("Glasses SDK failed to start: ${error.description}")
    }
    scope.launch {
      Wearables.registrationState.collect { value ->
        update {
          it.copy(
              registration = value.toString(),
              registered = value == RegistrationState.REGISTERED,
          )
        }
      }
    }
    scope.launch {
      Wearables.devices.collect { devices -> update { it.copy(deviceCount = devices.size) } }
    }
    scope.launch {
      selector.activeDeviceFlow().collect { device ->
        update { it.copy(hasActiveDevice = device != null) }
      }
    }
  }

  fun register(activity: Activity) {
    if (!wearablesReady) {
      fail("Accorde d'abord les autorisations Bluetooth et micro")
      return
    }
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: Activity) {
    if (wearablesReady) Wearables.startUnregistration(activity)
  }

  // ---- Claude key and usage ----

  /** The key stays on this phone, encrypted; it is only ever sent to api.anthropic.com. */
  fun setClaudeKey(value: String) {
    val key = value.trim()
    if (key.isEmpty()) return
    SecureStore.put(app, CLAUDE_KEY, key)
    update { it.copy(signedIn = true) }
    log("Clé Claude enregistrée")
  }

  fun clearClaudeKey() {
    if (_state.value.running && !_state.value.liveMode) stop()
    SecureStore.put(app, CLAUDE_KEY, null)
    update { it.copy(signedIn = false) }
    log("Clé Claude supprimée")
  }

  private fun monthTag(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

  /** Counters reset by themselves when the month changes. */
  private fun refreshUsage() {
    val prefs = prefs()
    val month = monthTag()
    if (prefs.getString("usage_month", null) != month) {
      prefs
          .edit()
          .putString("usage_month", month)
          .putInt("usage_questions", 0)
          .putLong("usage_in", 0)
          .putLong("usage_out", 0)
          .apply()
    }
    update {
      it.copy(
          monthQuestions = prefs.getInt("usage_questions", 0),
          monthInputTokens = prefs.getLong("usage_in", 0),
          monthOutputTokens = prefs.getLong("usage_out", 0),
      )
    }
  }

  private fun recordUsage(usage: Usage) {
    refreshUsage()
    val prefs = prefs()
    prefs
        .edit()
        .putInt("usage_questions", prefs.getInt("usage_questions", 0) + 1)
        .putLong("usage_in", prefs.getLong("usage_in", 0) + usage.inputTokens)
        .putLong("usage_out", prefs.getLong("usage_out", 0) + usage.outputTokens)
        .apply()
    refreshUsage()
    log("Coût de la question : ${usage.inputTokens} jetons envoyés, ${usage.outputTokens} reçus")
  }

  private fun limitReached(): Boolean {
    refreshUsage()
    val limit = _state.value.monthlyLimit
    return limit > 0 && _state.value.monthQuestions >= limit
  }

  fun setMonthlyLimit(value: Int) {
    val clean = value.coerceIn(0, 100_000)
    update { it.copy(monthlyLimit = clean) }
    prefs().edit().putInt("monthly_limit", clean).apply()
  }

  fun setIdleSeconds(value: Int) {
    val clean = value.coerceIn(0, 3600)
    update { it.copy(idleSeconds = clean) }
    prefs().edit().putInt("idle_seconds", clean).apply()
  }

  // ---- settings ----

  fun setModel(value: String) {
    update { it.copy(model = value) }
    prefs().edit().putString("model", value).apply()
  }

  fun setGlassesMic(value: Boolean) {
    update { it.copy(glassesMic = value) }
    prefs().edit().putBoolean("glasses_mic", value).apply()
  }

  fun setPhotoMode(value: Boolean) {
    update { it.copy(photoMode = value) }
    prefs().edit().putBoolean("photo_mode", value).apply()
  }

  fun setWakeWord(value: String) {
    update { it.copy(wakeWord = value) }
    prefs().edit().putString("wake_word", value).apply()
  }

  fun setLanguage(value: String) {
    update { it.copy(language = value) }
    prefs().edit().putString("language", value).apply()
  }

  fun setLiveMode(value: Boolean) {
    update { it.copy(liveMode = value) }
    prefs().edit().putBoolean("live_mode", value).apply()
  }

  fun setBargeIn(value: Boolean) {
    update { it.copy(bargeIn = value) }
    prefs().edit().putBoolean("barge_in", value).apply()
  }

  /** The key stays on this phone, encrypted; it is only ever sent to Google. */
  fun setGeminiKey(value: String) {
    val key = value.trim()
    if (key.isEmpty()) return
    SecureStore.put(app, GEMINI_KEY, key)
    update { it.copy(geminiKeySet = true) }
    setLiveMode(true)
    log("Gemini key saved, live voice is on")
  }

  fun clearGeminiKey() {
    if (_state.value.running && _state.value.liveMode) stop()
    SecureStore.put(app, GEMINI_KEY, null)
    update { it.copy(geminiKeySet = false) }
    setLiveMode(false)
    log("Gemini key deleted")
  }

  fun setTriggerPackage(value: String) {
    update { it.copy(triggerPackage = value) }
    prefs().edit().putString("trigger_package", value).apply()
  }

  /**
   * Must be switched on from the screen: the background service has to start while the app is
   * visible to be allowed to use the microphone later, when a gesture starts the conversation.
   */
  fun setStandby(value: Boolean) {
    if (value == _state.value.standby) return
    if (!value) {
      MediaWatch.stop()
      update { it.copy(standby = false, standbyAudio = "") }
      if (!_state.value.running && serviceStarted) {
        KeepAliveService.stop(app)
        serviceStarted = false
      }
      log("Standby off")
      return
    }
    if (!wearablesReady) {
      fail("Accorde d'abord les autorisations Bluetooth et micro")
      return
    }
    // Standby follows one audio device, so a car or other headphones cannot set it off.
    val glasses = MediaWatch.bluetoothOutputs(app).firstOrNull()
    if (glasses == null) {
      fail("Mets d'abord les lunettes : la veille suit l'appareil audio Bluetooth actif")
      return
    }
    if (!MediaWatch.start(app, _state.value.triggerPackage.trim(), glasses)) {
      fail("La veille a besoin de l'accès aux notifications : bouton sous l'interrupteur.")
      return
    }
    try {
      if (!serviceStarted) KeepAliveService.start(app)
      serviceStarted = true
    } catch (e: Exception) {
      MediaWatch.stop()
      fail("Impossible de démarrer la veille : ${e.message}")
      return
    }
    update { it.copy(standby = true, standbyAudio = glasses) }
    log("Standby on, watching ${_state.value.triggerPackage.trim()} while music goes to $glasses")
  }

  /** Called when the standby gesture arrives from the glasses. */
  fun toggleFromGesture() {
    if (_state.value.running) {
      log("Gesture: stopping")
      MediaWatch.beep(starting = false)
      stop()
    } else {
      log("Gesture: starting")
      MediaWatch.beep(starting = true)
      start(null)
    }
  }

  fun clearError() {
    update { it.copy(error = null) }
  }

  fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

  // ---- conversation: glasses side ----

  /**
   * Starts the conversation. [requestPermission] opens the Meta AI app to ask for camera access,
   * which needs the activity, so the screen passes it in. A start from a gesture has no screen
   * and passes null; camera access must already have been granted then.
   */
  fun start(requestPermission: (suspend (Permission) -> PermissionStatus)?) {
    if (_state.value.running) return
    if (_state.value.liveMode && !_state.value.geminiKeySet) {
      fail("Enregistre d'abord une clé Gemini")
      return
    }
    if (!_state.value.liveMode && !_state.value.signedIn) {
      fail("Enregistre d'abord ta clé API Claude")
      return
    }
    if (!wearablesReady) {
      fail("Accorde d'abord les autorisations Bluetooth et micro")
      return
    }
    update { it.copy(running = true, phase = Phase.CONNECTING, error = null) }
    scope.launch {
      var granted = false
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            granted = status == PermissionStatus.Granted
            // A start from the glasses cannot ask, so the answer itself is worth seeing.
            if (requestPermission == null) log("Gesture: Meta AI reports camera access as $status")
          }
          .onFailure { error, _ -> log("Camera permission check: ${error.description}") }
      if (!granted && requestPermission != null) {
        granted = requestPermission(Permission.CAMERA) == PermissionStatus.Granted
      }
      if (!_state.value.running) return@launch
      if (!granted) {
        if (requestPermission == null) MediaWatch.beep(starting = false)
        fail(
            if (requestPermission == null) {
              "Pas d'accès permanent à la caméra. Démarre une fois depuis l'app et choisis « Toujours autoriser » dans Meta AI."
            } else {
              "Meta AI n'a pas autorisé l'accès à la caméra des lunettes"
            },
        )
        shutdown()
        return@launch
      }
      try {
        if (!serviceStarted) KeepAliveService.start(app)
        serviceStarted = true
      } catch (e: Exception) {
        log("Could not start background mode: ${e.message}")
      }
      openSession()
    }
  }

  fun stop() {
    if (!_state.value.running) return
    log("Stopped")
    shutdown()
  }

  private fun openSession() {
    Wearables.createSession(selector)
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              sessionJobs +=
                  scope.launch { created.state.collect { onSessionState(created, it) } }
              sessionJobs +=
                  scope.launch {
                    created.errors.collect { error ->
                      log("Session error: ${error.description}")
                      update { it.copy(error = "Lunettes : ${error.description}") }
                    }
                  }
              created.start()
            },
            onFailure = { error, _ ->
              fail("Impossible d'ouvrir une session avec les lunettes : ${error.description}")
              shutdown()
            },
        )
  }

  private fun onSessionState(current: DeviceSession, value: DeviceSessionState) {
    update { it.copy(sessionState = value.toString()) }
    when (value) {
      DeviceSessionState.STARTED -> {
        sessionStarted = true
        if (camera == null) attachCamera(current) else resumeIfReady()
      }
      DeviceSessionState.PAUSED -> {
        sessionStarted = false
        pauseVoice("The glasses paused the session")
      }
      DeviceSessionState.STOPPED -> {
        if (_state.value.running) {
          log("The glasses ended the session")
          shutdown()
        }
      }
      else -> Unit
    }
  }

  private fun attachCamera(current: DeviceSession) {
    current
        .addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 7))
        .fold(
            onSuccess = { added ->
              camera = added
              val video = added.stream
              stream = video
              sessionJobs +=
                  scope.launch(Dispatchers.Default) {
                    video.videoStream.collect { frame -> Frames.offer(frame) }
                  }
              sessionJobs += scope.launch { video.state.collect { onStreamState(it) } }
              sessionJobs +=
                  scope.launch {
                    video.errorStream.collect { error ->
                      log("Video stream error: ${error.description}")
                    }
                  }
              video.start().onFailure { error, _ ->
                log("Video stream did not start: ${error.description}")
              }
              // Voice still works if the picture never arrives; say so and carry on without it.
              sessionJobs +=
                  scope.launch {
                    delay(STREAM_WAIT_MS)
                    if (_state.value.running && !streaming && !voiceActive) {
                      log("No video from the glasses after 10 seconds, continuing without it")
                      startVoice()
                    }
                  }
            },
            onFailure = { error, _ ->
              fail("Caméra des lunettes indisponible : ${error.description}")
              shutdown()
            },
        )
  }

  private fun onStreamState(value: StreamState) {
    update { it.copy(streamState = value.toString()) }
    when (value) {
      StreamState.STREAMING -> {
        streaming = true
        resumeIfReady()
      }
      // A single tap on the temple pauses the stream and a second one resumes it; the
      // conversation follows, which makes the tap a hands-free pause button.
      StreamState.PAUSED -> {
        streaming = false
        pauseVoice("En pause. Touche la branche pour reprendre")
      }
      StreamState.STOPPED,
      StreamState.CLOSED -> streaming = false
      else -> Unit
    }
  }

  private fun resumeIfReady() {
    if (_state.value.running && sessionStarted && streaming && !voiceActive) startVoice()
  }

  private fun shutdown() {
    voiceActive = false
    turnJob?.cancel()
    turnJob = null
    stt?.destroy()
    stt = null
    tts?.stop()
    live?.stop()
    live = null
    liveHandle = null
    liveAttempt = 0
    liveDrops = 0
    liveTurnDone = true
    AudioRouter.disable(app)
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    runCatching { camera?.stop() }
    runCatching { session?.stop() }
    camera = null
    stream = null
    session = null
    sessionStarted = false
    streaming = false
    history.clear()
    Frames.reset()
    // In standby the service stays up, waiting for the next gesture.
    if (serviceStarted && !_state.value.standby) {
      KeepAliveService.stop(app)
      serviceStarted = false
    }
    update {
      it.copy(
          running = false,
          phase = Phase.IDLE,
          partial = "",
          sessionState = "—",
          streamState = "—",
      )
    }
  }

  // ---- conversation: voice side ----

  private fun startVoice() {
    if (voiceActive) return
    voiceActive = true
    lastActivityAt = SystemClock.elapsedRealtime()
    if (_state.value.liveMode) {
      startLive()
      return
    }
    scope.launch {
      val settings = _state.value
      val routed = settings.glassesMic && AudioRouter.enable(app)
      if (settings.glassesMic && !routed) {
        log("Glasses microphone unavailable over Bluetooth, using the phone microphone")
      }
      if (!voiceActive) return@launch

      val speech = tts ?: Tts(app) { ok -> if (!ok) log("Text-to-speech failed to start") }.also { tts = it }
      withTimeoutOrNull(3000) { while (!speech.ready) delay(50) }
      if (!speech.configure(speechLanguage(), routed)) {
        log("The phone has no text-to-speech voice for ${speechLanguage()}")
      }

      val recognizer = stt ?: Stt(app, sttCallbacks).also { stt = it }
      if (!recognizer.isAvailable()) {
        fail("Aucun service de reconnaissance vocale sur ce téléphone")
        shutdown()
        return@launch
      }
      if (voiceActive) listen()
    }
  }

  private fun pauseVoice(reason: String) {
    if (!voiceActive) return
    voiceActive = false
    turnJob?.cancel()
    turnJob = null
    stt?.cancel()
    tts?.stop()
    // The live session is closed too; its handle lets the next one pick the conversation up.
    live?.stop()
    live = null
    liveAttempt = 0
    // Free the hands-free link so music can play through the glasses while paused.
    AudioRouter.disable(app)
    log(reason)
    update { it.copy(phase = Phase.PAUSED, partial = "") }
  }

  private fun listen() {
    if (!voiceActive) return
    update { it.copy(phase = Phase.LISTENING, partial = "") }
    try {
      stt?.start(speechLanguage())
    } catch (e: Exception) {
      log("Speech recognition did not start: ${e.message}")
      listenAfter(1000)
    }
  }

  private fun speechLanguage(): String =
      _state.value.language.ifBlank { Locale.getDefault().toLanguageTag() }

  private fun listenAfter(delayMs: Long) {
    scope.launch {
      delay(delayMs)
      if (voiceActive && turnJob?.isActive != true) listen()
    }
  }

  private val sttCallbacks =
      object : Stt.Callbacks {
        override fun onSpeechStart() {}

        override fun onPartial(text: String) {
          update { it.copy(partial = text) }
        }

        override fun onFinal(text: String) {
          errorStreak = 0
          onUtterance(text)
        }

        override fun onError(code: Int) {
          when (code) {
            // Nobody spoke: just listen again.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
              if (idleExpired()) stopForIdle() else listenAfter(150)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              stt?.cancel()
              listenAfter(500)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
              fail("Pas d'accès au micro")
              shutdown()
            }
            else -> {
              errorStreak += 1
              log("Speech recognition error $code")
              listenAfter(if (errorStreak > 5) 3000 else 800)
            }
          }
        }
      }

  /** True once nobody has asked anything for the configured time. */
  private fun idleExpired(): Boolean {
    val seconds = _state.value.idleSeconds
    if (seconds <= 0 || _state.value.liveMode) return false
    return SystemClock.elapsedRealtime() - lastActivityAt > seconds * 1000L
  }

  /** Stops listening by itself, so the microphone is never left open for nothing. */
  private fun stopForIdle() {
    log("Pas de question depuis ${_state.value.idleSeconds} s : arrêt automatique")
    MediaWatch.beep(starting = false)
    stop()
  }

  private fun onUtterance(text: String) {
    val heard = text.trim()
    if (heard.isEmpty()) {
      if (idleExpired()) stopForIdle() else listen()
      return
    }
    var question = heard
    val wakeWord = _state.value.wakeWord.trim()
    if (wakeWord.isNotEmpty()) {
      // With a wake word set, everything that does not contain it is ignored.
      val index = heard.lowercase().indexOf(wakeWord.lowercase())
      if (index < 0) {
        if (idleExpired()) stopForIdle() else listen()
        return
      }
      val after = (index + wakeWord.length).coerceAtMost(heard.length)
      question = heard.substring(after).trim().trimStart(',', '.', '!', '?', ' ')
      if (question.isEmpty()) {
        listen()
        return
      }
    }
    turnJob = scope.launch { runTurn(question) }
  }

  private suspend fun runTurn(question: String) {
    val startedAt = SystemClock.elapsedRealtime()
    lastActivityAt = startedAt
    if (limitReached()) {
      fail("Quota mensuel atteint (${_state.value.monthlyLimit} questions). Modifiable dans les réglages.")
      update { it.copy(phase = Phase.SPEAKING) }
      tts?.speak("Quota mensuel de questions atteint.")
      awaitSpeechEnd()
      listen()
      return
    }
    update {
      it.copy(
          phase = Phase.THINKING,
          question = question,
          answer = "",
          partial = "",
          latencyMs = null,
          error = null,
      )
    }

    val jpeg = withContext(Dispatchers.Default) { acquireFrame() }
    val preview = withContext(Dispatchers.Default) { jpeg?.let { Frames.preview(it) } }
    update { it.copy(frame = preview, frameInfo = Frames.info) }
    if (jpeg == null) log("Pas d'image des lunettes, réponse sans photo")

    val answer = StringBuilder()
    val chunker = SentenceChunker()
    var spoke = false
    fun say(sentence: String) {
      val clean = cleanForSpeech(sentence)
      if (clean.isEmpty()) return
      if (!spoke) {
        spoke = true
        val latency = SystemClock.elapsedRealtime() - startedAt
        update { it.copy(phase = Phase.SPEAKING, latencyMs = latency) }
      }
      tts?.speak(clean)
    }

    try {
      val settings = _state.value
      val key = SecureStore.get(app, CLAUDE_KEY) ?: throw ApiException(401, "Aucune clé Claude")
      val body =
          Claude.buildBody(
              model = settings.model,
              system = instructions(jpeg != null),
              history = history.toList(),
              question = question,
              jpeg = jpeg,
              maxTokens = MAX_ANSWER_TOKENS,
          )
      Claude.stream(key, body).collect { event ->
        when (event) {
          is ClaudeEvent.Text -> {
            answer.append(event.delta)
            update { it.copy(answer = answer.toString()) }
            chunker.push(event.delta).forEach { say(it) }
          }
          is ClaudeEvent.Done -> recordUsage(event.usage)
        }
      }
      chunker.flush()?.let { say(it) }

      val text = answer.toString().trim()
      if (text.isNotEmpty()) {
        history.addLast(Turn(question, text))
        while (history.size > MAX_HISTORY) history.removeFirst()
      }
      awaitSpeechEnd()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val status = (e as? ApiException)?.status ?: 0
      fail(
          when (status) {
            401 -> "Clé API Claude refusée : vérifie-la dans l'app"
            402, 403 -> "Accès refusé par Anthropic : crédit ou plafond de dépenses atteint ?"
            429 -> "Trop de requêtes ou plafond atteint côté Anthropic"
            else -> "Échec de la requête Claude : ${e.message}"
          },
      )
      tts?.speak(spokenError(status))
      awaitSpeechEnd()
      if (status == 401) {
        shutdown()
        return
      }
    }
    lastActivityAt = SystemClock.elapsedRealtime()
    listen()
  }

  /** Spoken aloud through the glasses when a question fails. */
  private fun spokenError(status: Int): String =
      when (status) {
        401 -> "Ma clé d'accès est refusée."
        402, 403, 429 -> "Le plafond de dépenses semble atteint."
        else -> "La requête a échoué."
      }

  /** Listening must not start while the answer is still playing, or it would hear itself. */
  private suspend fun awaitSpeechEnd() {
    val speech = tts ?: return
    delay(150)
    while (speech.isSpeaking) delay(100)
    delay(250)
  }

  /** Runs off the main thread. Video frames are instant; a photo is sharper but takes longer. */
  private suspend fun acquireFrame(): ByteArray? {
    val photoFirst = _state.value.photoMode
    if (photoFirst) capturePhotoJpeg()?.let { return it }
    Frames.latestJpeg()?.let { return it }
    return if (photoFirst) null else capturePhotoJpeg()
  }

  private suspend fun capturePhotoJpeg(): ByteArray? {
    val video = stream ?: return null
    if (!streaming) return null
    var result: ByteArray? = null
    withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
      video
          .capturePhoto()
          .onSuccess { photo ->
            result =
                when (photo) {
                  is PhotoData.Bitmap -> Frames.bitmapToJpeg(photo.bitmap)
                  is PhotoData.HEIC -> Frames.heicToJpeg(photo.data)
                }
          }
          .onFailure { error, _ -> log("Photo from the glasses: ${error.description}") }
    }
    return result
  }

  private fun instructions(hasImage: Boolean): String {
    val picture =
        if (hasImage) {
          "Une photo prise à l'instant par la caméra des lunettes accompagne la question : " +
              "c'est ce que l'utilisateur a devant les yeux. Sers-t'en quand la question porte " +
              "sur ce qu'il voit, sans la décrire si on ne te le demande pas. Si l'objet de la " +
              "question n'est pas visible ou pas lisible sur la photo, dis-le."
        } else {
          "Aucune image de la caméra n'est disponible. Si la question porte sur ce que " +
              "l'utilisateur a devant lui, dis que tu ne le vois pas."
        }
    return "Tu es Claude, un assistant vocal intégré à des lunettes connectées. L'utilisateur " +
        "te parle à voix haute et ta réponse lui est lue dans les branches des lunettes. Réponds " +
        "dans la langue de la question, par défaut en français, en le tutoyant. Sois bref et " +
        "concret : une à trois phrases, sauf si on te demande plus. Pas de listes, de mise en " +
        "forme, d'émojis ni de liens ; écris les nombres et unités comme on les prononce. " +
        "Quand on te demande de l'aide sur une tâche manuelle, donne l'étape suivante à faire, " +
        "pas toute la procédure. " +
        picture
  }

  private fun cleanForSpeech(text: String): String =
      text.replace(Regex("[*#`_>|]"), "").replace(Regex("\\s+"), " ").trim()

  // ---- conversation: live voice ----

  private fun startLive() {
    scope.launch {
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key == null) {
        fail("No Gemini API key")
        shutdown()
        return@launch
      }
      val wantGlasses = _state.value.glassesMic
      val onGlasses = AudioRouter.enableCall(app, wantGlasses)
      if (wantGlasses && !onGlasses) {
        log("Glasses audio unavailable over Bluetooth, using the phone")
      }
      if (voiceActive) connectLive(key)
    }
  }

  private fun connectLive(key: String) {
    live?.stop()
    val events = LiveEvents()
    val session =
        GeminiLive(
            apiKey = key,
            instructions = liveInstructions(),
            resumeHandle = if (liveAttempt == 0) liveHandle else null,
            fullSetup = liveAttempt < 2,
            bargeIn = _state.value.bargeIn,
            inputDevice = AudioRouter.inputDevice(app),
            callAudio = AudioRouter.isRouted,
            frameSource = { Frames.latestJpeg(LIVE_JPEG_QUALITY) },
            events = events,
        )
    events.owner = session
    live = session
    update { it.copy(phase = Phase.CONNECTING, partial = "") }
    session.start()
  }

  /** Session callbacks come from background threads; each is replayed on the main thread. */
  private class LiveEvents : GeminiLive.Events {
    var owner: GeminiLive? = null

    /** Drops callbacks from a session that has since been replaced or stopped. */
    private fun onMain(block: () -> Unit) {
      scope.launch { if (live === owner && voiceActive) block() }
    }

    override fun onReady() = onMain {
      liveDrops = 0
      liveTurnDone = true
      log(if (liveAttempt < 2) "Gemini Live connected" else "Gemini Live connected, basic setup")
      update { it.copy(phase = Phase.LISTENING, error = null) }
    }

    override fun onUserText(delta: String) = onMain {
      if (liveTurnDone) {
        liveTurnDone = false
        update { it.copy(question = delta, answer = "") }
      } else {
        update { it.copy(question = it.question + delta) }
      }
    }

    override fun onModelText(delta: String) = onMain {
      if (liveTurnDone) {
        liveTurnDone = false
        update { it.copy(question = "", answer = delta) }
      } else {
        update { it.copy(answer = it.answer + delta) }
      }
    }

    override fun onModelSpeaking(speaking: Boolean) = onMain {
      update { it.copy(phase = if (speaking) Phase.SPEAKING else Phase.LISTENING) }
    }

    override fun onTurnComplete() = onMain { liveTurnDone = true }

    override fun onResumeHandle(handle: String) = onMain { liveHandle = handle }

    override fun onGoAway() = onMain {
      // The server ends every connection after about ten minutes and says so in advance.
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key != null) {
        log("Gemini is moving the conversation to a new connection")
        liveAttempt = 0
        connectLive(key)
      }
    }

    override fun onFrameSent(jpeg: ByteArray) {
      val preview = Frames.preview(jpeg)
      onMain { update { it.copy(frame = preview, frameInfo = Frames.info) } }
    }

    override fun onClosed(reason: String, wasReady: Boolean) = onMain {
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key == null) {
        shutdown()
        return@onMain
      }
      val badKey =
          reason.contains("API key", ignoreCase = true) ||
              reason.contains("HTTP 401") ||
              reason.contains("HTTP 403")
      val outOfQuota =
          reason.contains("quota", ignoreCase = true) ||
              reason.contains("RESOURCE_EXHAUSTED") ||
              reason.contains("HTTP 429")
      when {
        badKey -> {
          fail("Gemini rejected the API key: $reason")
          shutdown()
        }
        outOfQuota -> {
          fail("Gemini quota reached: $reason")
          shutdown()
        }
        // Refused before the session opened: retry with fewer options each time.
        !wasReady -> {
          liveAttempt += 1
          if (liveAttempt > 2) {
            fail("Gemini refused the connection: $reason")
            shutdown()
          } else {
            log("Gemini closed the connection ($reason), retrying with fewer options")
            connectLive(key)
          }
        }
        else -> {
          liveDrops += 1
          if (liveDrops > 5) {
            fail("The connection to Gemini keeps dropping: $reason")
            shutdown()
          } else {
            log("Connection to Gemini lost ($reason), reconnecting")
            liveAttempt = 0
            val dropped = owner
            scope.launch {
              delay(400L * liveDrops)
              if (voiceActive && live === dropped) connectLive(key)
            }
          }
        }
      }
    }

    override fun onLog(message: String) {
      log(message)
    }
  }

  private fun liveInstructions(): String =
      "You are a voice assistant in smart glasses. You hear the user and see video from the " +
          "camera of their glasses: it is what is in front of them right now. Talk naturally " +
          "and briefly, like a person in conversation: one to three sentences unless asked " +
          "for more. Always answer in the language the user speaks to you. Use the video when " +
          "asked about what is visible, and do not describe it unasked. If what they ask " +
          "about is not visible or not legible, say so. Respond only to speech addressed to you."

  // ---- plumbing ----

  private fun prefs(): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun update(block: (UiState) -> UiState) {
    _state.update(block)
  }

  private fun fail(message: String) {
    log(message)
    update { it.copy(error = message) }
  }

  /** Safe from any thread. The log is on screen because there is no other way to see inside. */
  fun log(message: String) {
    Log.i(TAG, message)
    val snapshot =
        synchronized(logLines) {
          logLines.addLast("${clock.format(Date())}  $message")
          while (logLines.size > MAX_LOG) logLines.removeFirst()
          logLines.toList()
        }
    update { it.copy(log = snapshot) }
  }
}
