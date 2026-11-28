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
import android.speech.tts.TextToSpeech
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
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

/** Monthly budget in US dollars, the currency Anthropic bills in. */
const val DEFAULT_MONTHLY_BUDGET = 5
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
    /** Dollars allowed per calendar month; 0 means no limit. */
    val monthlyBudget: Int = DEFAULT_MONTHLY_BUDGET,
    /** The conversation stops after this many seconds without a question; 0 means never. */
    val idleSeconds: Int = DEFAULT_IDLE_SECONDS,
    val monthQuestions: Int = 0,
    val monthInputTokens: Long = 0,
    val monthOutputTokens: Long = 0,
    /** Estimated spend this month, in US dollars. */
    val monthCost: Double = 0.0,
    /** The installed version, shown at the top of the screen. */
    val version: String = "",
    val testing: Boolean = false,
    val testResult: String = "",
    /** Update state: what to show, and the newer build when there is one (0 when none). */
    val updateText: String = "",
    val updateBuild: Int = 0,
    val updating: Boolean = false,
    /** Chosen text-to-speech voice ("" = the language's default) and speaking speed. */
    val voiceName: String = "",
    val speechRate: Float = 1.1f,
    /** Voices for the current language: technical name, then the label shown. */
    val voices: List<Pair<String, String>> = emptyList(),
    /** Translation mode: listens in the other person's language, reads the French aloud. */
    val translateMode: Boolean = false,
    val partnerLanguage: String = "ko-KR",
    /** Also treat the music starting as a gesture while the phone is in use. */
    val triggerScreenOn: Boolean = false,
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
  private val fileClock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
  private val logWriter = Executors.newSingleThreadExecutor()
  private const val JOURNAL = "journal.txt"
  private const val JOURNAL_MAX_BYTES = 400_000L

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
  /** The last non-empty words of the current utterance, kept even if the engine blanks them. */
  private var lastHeard = ""
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
    val version =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    // The journal from before Android closed the app, so problems can still be traced.
    runCatching {
      val previous = journalFile().takeIf { it.exists() }?.readLines()?.takeLast(MAX_LOG).orEmpty()
      synchronized(logLines) { previous.forEach { logLines.addLast(it) } }
    }
    update { it.copy(version = version) }
    log("—— Démarrage de l'app, version $version ——")
    if (!prefs().getString("wake_word", "").isNullOrEmpty()) {
      prefs().edit().remove("wake_word").apply()
      log("Mot d'appel supprimé : toutes les phrases sont désormais envoyées")
    }
    val prefs = prefs()
    update {
      it.copy(
          signedIn = SecureStore.get(app, CLAUDE_KEY) != null,
          model =
              (prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).let {
                if (it in FALLBACK_MODELS) it else DEFAULT_MODEL
              },
          monthlyBudget = prefs.getInt("monthly_budget", DEFAULT_MONTHLY_BUDGET),
          idleSeconds = prefs.getInt("idle_seconds", DEFAULT_IDLE_SECONDS),
          voiceName = prefs.getString("tts_voice", "") ?: "",
          speechRate = prefs.getFloat("tts_rate", 1.1f),
          translateMode = prefs.getBoolean("translate_mode", false),
          partnerLanguage = prefs.getString("partner_language", "ko-KR") ?: "ko-KR",
          triggerScreenOn = prefs.getBoolean("trigger_screen_on", false),
          glassesMic = prefs.getBoolean("glasses_mic", true),
          photoMode = prefs.getBoolean("photo_mode", false),
          // The gesture opens the conversation: a wake word only made phrases disappear.
          wakeWord = "",
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

  /** Dollars per million tokens, input then output, from Anthropic's price list. */
  private fun prices(model: String): Pair<Double, Double> =
      when {
        "opus" in model -> 4.0 to 20.0
        "sonnet" in model -> 2.0 to 10.0
        else -> 0.10 to 0.50
      }

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
          .putLong("usage_micro_usd", 0)
          .apply()
    }
    update {
      it.copy(
          monthQuestions = prefs.getInt("usage_questions", 0),
          monthInputTokens = prefs.getLong("usage_in", 0),
          monthOutputTokens = prefs.getLong("usage_out", 0),
          monthCost = prefs.getLong("usage_micro_usd", 0) / 1_000_000.0,
      )
    }
  }

  private fun recordUsage(usage: Usage, model: String, countQuestion: Boolean) {
    refreshUsage()
    val (inPrice, outPrice) = prices(model)
    // Prices are per million tokens, so tokens × price is already in micro-dollars.
    val micro = (usage.inputTokens * inPrice + usage.outputTokens * outPrice).toLong()
    val prefs = prefs()
    prefs
        .edit()
        .putInt("usage_questions", prefs.getInt("usage_questions", 0) + if (countQuestion) 1 else 0)
        .putLong("usage_in", prefs.getLong("usage_in", 0) + usage.inputTokens)
        .putLong("usage_out", prefs.getLong("usage_out", 0) + usage.outputTokens)
        .putLong("usage_micro_usd", prefs.getLong("usage_micro_usd", 0) + micro)
        .apply()
    refreshUsage()
    log(
        "Appel : ${usage.inputTokens} jetons envoyés, ${usage.outputTokens} reçus, " +
            "≈ %.4f \$".format(Locale.US, micro / 1_000_000.0),
    )
  }

  private fun budgetReached(): Boolean {
    refreshUsage()
    val budget = _state.value.monthlyBudget
    return budget > 0 && _state.value.monthCost >= budget
  }

  fun setMonthlyBudget(value: Int) {
    val clean = value.coerceIn(0, 10_000)
    update { it.copy(monthlyBudget = clean) }
    prefs().edit().putInt("monthly_budget", clean).apply()
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
    prefs().edit().putBoolean("standby_wanted", value).apply()
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

  /**
   * Switches standby back on when the screen opens, if it was on before Android closed the app.
   * Quiet when the glasses are not connected: it will try again next time.
   */
  fun rearmStandby() {
    if (_state.value.standby || !wearablesReady) return
    if (!prefs().getBoolean("standby_wanted", false)) return
    if (!MediaWatch.hasAccess(app) || MediaWatch.bluetoothOutputs(app).isEmpty()) return
    log("Veille réactivée automatiquement")
    setStandby(true)
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
      var granted = cameraGranted("avant demande")
      if (!granted && requestPermission != null) {
        // Meta AI may never hand the answer back (Android can recreate this screen meanwhile),
        // so the wait is bounded and the status is read again afterwards.
        val answer = withTimeoutOrNull(90_000) { requestPermission(Permission.CAMERA) }
        log("Réponse de Meta AI à la demande caméra : ${answer ?: "aucune (délai dépassé)"}")
        granted = answer == PermissionStatus.Granted || cameraGranted("après demande")
      }
      if (!_state.value.running) return@launch
      if (!granted) {
        if (requestPermission == null) MediaWatch.beep(starting = false)
        fail(
            if (glassesMissing) {
              "Lunettes introuvables : vérifie qu'elles sont allumées et connectées, et que la " +
                  "section 1 indique « App liée aux lunettes »."
            } else if (requestPermission == null) {
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

  /** What Meta AI says about camera access right now, written to the journal. */
  private var glassesMissing = false

  private suspend fun cameraGranted(moment: String): Boolean {
    glassesMissing = false
    // Right after a gesture the glasses may not be discovered yet: give them a few seconds.
    repeat(4) { attempt ->
      var granted = false
      var retry = false
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            granted = status == PermissionStatus.Granted
            log("Accès caméra ($moment) : $status")
          }
          .onFailure { error, _ ->
            log("Vérification de l'accès caméra ($moment) : ${error.description}")
            retry = error.description.contains("discovered", ignoreCase = true)
            if (retry) glassesMissing = true
          }
      if (granted || !retry || attempt == 3) return granted
      delay(1500)
    }
    return false
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
      if (!speech.configure(
          speechLanguage(),
          routed,
          _state.value.voiceName,
          _state.value.speechRate,
      )) {
        log("The phone has no text-to-speech voice for ${speechLanguage()}")
      }

      val recognizer =
          stt ?: Stt(app, sttCallbacks).also {
            stt = it
            log("Reconnaissance vocale : ${it.engineName}")
          }
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
    lastHeard = ""
    update { it.copy(phase = Phase.LISTENING, partial = "") }
    try {
      stt?.start(listeningLanguage())
    } catch (e: Exception) {
      log("La reconnaissance vocale n'a pas démarré : ${e.message}")
      if (stt?.useOnDevice == true) {
        log("Retour au moteur du téléphone")
        stt?.fallBackToDefault()
      }
      listenAfter(1000)
    }
  }

  private fun speechLanguage(): String =
      _state.value.language.ifBlank { Locale.getDefault().toLanguageTag() }

  /** What the recognizer listens for: the other person's language in translation mode. */
  private fun listeningLanguage(): String =
      if (_state.value.translateMode) _state.value.partnerLanguage.ifBlank { "ko-KR" }
      else speechLanguage()

  fun setTranslateMode(value: Boolean) {
    update { it.copy(translateMode = value) }
    prefs().edit().putBoolean("translate_mode", value).apply()
    // The recognizer is tied to a language: a fresh one will pick up the new setting.
    stt?.destroy()
    if (voiceActive) listenAfter(300)
  }

  fun setTriggerScreenOn(value: Boolean) {
    update { it.copy(triggerScreenOn = value) }
    prefs().edit().putBoolean("trigger_screen_on", value).apply()
  }

  fun setPartnerLanguage(value: String) {
    update { it.copy(partnerLanguage = value) }
    prefs().edit().putString("partner_language", value).apply()
  }

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
          // Recognizers may end with an empty partial: never let it erase what was heard.
          if (text.isBlank()) return
          lastHeard = text
          // Talking counts as activity: never stop for idleness in the middle of a sentence.
          lastActivityAt = SystemClock.elapsedRealtime()
          update { it.copy(partial = text) }
        }

        override fun onFinal(text: String) {
          errorStreak = 0
          // Samsung's recognizer can show the words as partials, then return an empty final
          // result. The last partial is what was said.
          val partial = lastHeard.trim()
          val heard = text.trim().ifEmpty { partial }
          log("Fin d'écoute : résultat final « ${text.trim()} », dernier texte entendu « $partial »")
          onUtterance(heard)
        }

        override fun onError(code: Int) {
          // Some recognizers (Samsung's among them) show the words, then end with an error
          // instead of a final result. What was heard is still the question: send it.
          val heard = lastHeard.trim()
          if (heard.isNotEmpty() && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            log("Reconnaissance vocale : erreur $code après « $heard », envoi quand même")
            errorStreak = 0
            onUtterance(heard)
            return
          }
          when (code) {
            // Nobody spoke: just listen again.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
              val level = stt?.maxRms ?: -100f
              log("Écoute sans phrase reconnue (code $code, niveau sonore max %.0f)".format(level))
              if (idleExpired()) stopForIdle() else listenAfter(150)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              stt?.cancel()
              listenAfter(500)
            }
            // The on-device engine has no model for this language, or is not usable here.
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
              if (stt?.useOnDevice == true) {
                log("Moteur Google indisponible pour cette langue (code $code), retour au moteur du téléphone")
                stt?.fallBackToDefault()
                listenAfter(400)
              } else {
                fail(
                    "La reconnaissance vocale ne gère pas la langue ${listeningLanguage()}. " +
                        "Installe-la dans Speech Services by Google → Langues.",
                )
                shutdown()
              }
            }
            // The recognition service dropped the connection: this instance is dead, make a new
            // one rather than retrying it forever.
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT -> {
              errorStreak += 1
              log("Reconnaissance vocale déconnectée (code $code), redémarrage")
              stt?.destroy()
              listenAfter(if (errorStreak > 5) 3000 else 700)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
              fail("Pas d'accès au micro")
              shutdown()
            }
            else -> {
              errorStreak += 1
              log("Erreur de reconnaissance vocale $code")
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

  /** Lower case without accents, same length as the input for these alphabets. */
  private fun fold(text: String): String =
      java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
          .replace(Regex("\\p{Mn}"), "")

  private fun onUtterance(text: String) {
    val heard = text.trim()
    if (heard.isEmpty()) {
      log("Rien à envoyer, j'écoute à nouveau")
      if (idleExpired()) stopForIdle() else listen()
      return
    }
    // "Merci mon pote" closes the conversation, in any mode.
    if (fold(heard).contains("merci mon pote")) {
      log("Phrase de clôture entendue : fin de la conversation")
      scope.launch {
        tts?.speak("À plus tard.")
        awaitSpeechEnd()
        MediaWatch.beep(starting = false)
        stop()
      }
      return
    }
    if (_state.value.translateMode) {
      log("Entendu (${listeningLanguage()}) : « $heard »")
      turnJob = scope.launch { runTranslation(heard) }
      return
    }
    var question = heard
    val wakeWord = _state.value.wakeWord.trim()
    // The wake word only opens a conversation: once Claude has answered, follow-ups need none.
    if (wakeWord.isNotEmpty() && history.isEmpty()) {
      // Accents and case are ignored, since the recognizer may spell it differently.
      val index = fold(heard).indexOf(fold(wakeWord))
      if (index < 0) {
        log("Phrase ignorée, le mot d'appel « $wakeWord » n'y est pas : « $heard »")
        if (idleExpired()) stopForIdle() else listen()
        return
      }
      // The wake word can be anywhere in the sentence: remove it, keep everything else.
      val end = (index + wakeWord.length).coerceAtMost(heard.length)
      question =
          (heard.substring(0, index) + " " + heard.substring(end))
              .trim()
              .trim(',', '.', '!', '?', ' ')
      if (question.isEmpty()) {
        log("Mot d'appel seul entendu, j'attends la question")
        listen()
        return
      }
    }
    log("Question : « $question »")
    turnJob = scope.launch { runTurn(question) }
  }

  /**
   * Translation mode: what the other person said, translated into French by Sonnet and read
   * aloud. A short history of the exchange is kept so names and context carry over.
   */
  private suspend fun runTranslation(heard: String) {
    lastActivityAt = SystemClock.elapsedRealtime()
    if (budgetReached()) {
      fail("Budget mensuel atteint (${_state.value.monthlyBudget} \$). Modifiable dans l'app.")
      tts?.speak("Le budget du mois est atteint.")
      awaitSpeechEnd()
      listen()
      return
    }
    val startedAt = SystemClock.elapsedRealtime()
    update {
      it.copy(phase = Phase.THINKING, question = heard, answer = "", partial = "", error = null)
    }
    val model = "claude-sonnet-5-5"
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
      val key = SecureStore.get(app, CLAUDE_KEY) ?: throw ApiException(401, "Aucune clé Claude")
      val messages = org.json.JSONArray()
      for (turn in history) {
        messages.put(Claude.textMessage("user", turn.question))
        messages.put(Claude.textMessage("assistant", turn.answer))
      }
      messages.put(Claude.textMessage("user", heard))
      val system =
          "Tu es un interprète. Le texte vient d'une reconnaissance vocale réglée sur la langue " +
              "${listeningLanguage()} : il peut contenir des erreurs de transcription, corrige-les " +
              "d'après le sens probable. Traduis-le en français naturel et oral, fidèlement, " +
              "sans commentaire, sans guillemets, sans romanisation. Si le texte est " +
              "incompréhensible, dis seulement : je n'ai pas compris."
      Claude.stream(key, Claude.buildBody(model, system, messages, 400)).collect { event ->
        when (event) {
          is ClaudeEvent.Text -> {
            answer.append(event.delta)
            update { it.copy(answer = answer.toString()) }
            chunker.push(event.delta).forEach { say(it) }
          }
          is ClaudeEvent.ToolUse -> Unit
          is ClaudeEvent.Done -> recordUsage(event.usage, model, countQuestion = true)
        }
      }
      chunker.flush()?.let { say(it) }
      val text = answer.toString().trim()
      log("Traduction : « $text »")
      if (text.isNotEmpty()) {
        history.addLast(Turn(heard, text))
        while (history.size > MAX_HISTORY) history.removeFirst()
      }
      awaitSpeechEnd()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      fail("Échec de la traduction : ${e.message}")
      tts?.speak("La traduction a échoué.")
      awaitSpeechEnd()
    }
    lastActivityAt = SystemClock.elapsedRealtime()
    stt?.destroy()
    listenAfter(600)
  }

  private suspend fun runTurn(question: String) {
    val startedAt = SystemClock.elapsedRealtime()
    lastActivityAt = startedAt
    if (budgetReached()) {
      fail("Budget mensuel atteint (${_state.value.monthlyBudget} \$). Modifiable dans l'app.")
      update { it.copy(phase = Phase.SPEAKING) }
      tts?.speak("Le budget du mois est atteint.")
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
          frame = null,
          frameInfo = "—",
      )
    }

    // Sound cues: a beep says the question left; a soft tick says Claude is still working.
    MediaWatch.beep(starting = true)
    val ticker =
        scope.launch {
          delay(1500)
          // Stops by itself if the turn is cancelled or ends without a cancel reaching it.
          while (voiceActive && turnJob?.isActive == true) {
            MediaWatch.tick()
            delay(1500)
          }
        }
    val answer = StringBuilder()
    val chunker = SentenceChunker()
    var spoke = false
    fun say(sentence: String) {
      val clean = cleanForSpeech(sentence)
      if (clean.isEmpty()) return
      if (!spoke) {
        spoke = true
        ticker.cancel()
        val latency = SystemClock.elapsedRealtime() - startedAt
        update { it.copy(phase = Phase.SPEAKING, latencyMs = latency) }
      }
      tts?.speak(clean)
    }

    try {
      val settings = _state.value
      val key = SecureStore.get(app, CLAUDE_KEY) ?: throw ApiException(401, "Aucune clé Claude")
      val messages = org.json.JSONArray()
      for (turn in history) {
        messages.put(Claude.textMessage("user", turn.question))
        messages.put(Claude.textMessage("assistant", turn.answer))
      }
      messages.put(Claude.textMessage("user", question))

      // First call: Claude answers directly, or asks to look through the glasses.
      var toolId: String? = null
      var stopReason = ""
      Claude.stream(key, Claude.buildBody(settings.model, instructions(), messages, MAX_ANSWER_TOKENS))
          .collect { event ->
            when (event) {
              is ClaudeEvent.Text -> {
                answer.append(event.delta)
                update { it.copy(answer = answer.toString()) }
                chunker.push(event.delta).forEach { say(it) }
              }
              is ClaudeEvent.ToolUse -> {
                if (event.name == Claude.LOOK_TOOL) toolId = event.id
              }
              is ClaudeEvent.Done -> {
                stopReason = event.stopReason
                recordUsage(event.usage, settings.model, countQuestion = true)
              }
            }
          }
      chunker.flush()?.let { say(it) }

      val lookId = toolId
      if (stopReason == "tool_use" && lookId != null) {
        log("Claude demande à regarder")
        update { it.copy(phase = Phase.THINKING) }
        val jpeg = withContext(Dispatchers.Default) { acquireFrame() }
        val preview = withContext(Dispatchers.Default) { jpeg?.let { Frames.preview(it) } }
        update { it.copy(frame = preview, frameInfo = Frames.info) }
        if (jpeg == null) log("Pas d'image des lunettes")
        messages.put(Claude.toolCallMessage(answer.toString(), lookId, Claude.LOOK_TOOL))
        messages.put(Claude.toolResultMessage(lookId, jpeg))
        if (answer.isNotEmpty()) answer.append(' ')
        // Second call: the answer, now with the picture.
        Claude.stream(key, Claude.buildBody(settings.model, instructions(), messages, MAX_ANSWER_TOKENS))
            .collect { event ->
              when (event) {
                is ClaudeEvent.Text -> {
                  answer.append(event.delta)
                  update { it.copy(answer = answer.toString()) }
                  chunker.push(event.delta).forEach { say(it) }
                }
                is ClaudeEvent.ToolUse -> Unit
                is ClaudeEvent.Done -> recordUsage(event.usage, settings.model, countQuestion = false)
              }
            }
        chunker.flush()?.let { say(it) }
      }

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
      val detail = e.message.orEmpty()
      val limit =
          status == 429 ||
              detail.contains("usage limit", ignoreCase = true) ||
              detail.contains("credit balance", ignoreCase = true)
      ticker.cancel()
      fail(
          when {
            status == 401 -> "Clé API Claude refusée : vérifie-la dans l'app"
            limit ->
                "Plafond de dépenses ou crédit épuisé côté Anthropic (console.anthropic.com → " +
                    "Settings → Limits / Billing). Détail : $detail"
            else -> "Échec de la requête Claude : $detail"
          },
      )
      tts?.speak(
          when {
            status == 401 -> "Ma clé d'accès est refusée."
            limit -> "Le plafond de dépenses du compte Anthropic est atteint."
            else -> "La requête a échoué."
          },
      )
      awaitSpeechEnd()
      if (status == 401) {
        ticker.cancel()
        shutdown()
        return
      }
    }
    ticker.cancel()
    lastActivityAt = SystemClock.elapsedRealtime()
    // Samsung's recognizer can go deaf when reused right after speech output: start a fresh one,
    // after a pause, because recreating it at once makes its service disconnect (error 11).
    stt?.destroy()
    log("Réponse terminée, j'écoute à nouveau")
    listenAfter(600)
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

  private fun instructions(): String =
      "Tu es Claude, un assistant vocal intégré à des lunettes connectées. L'utilisateur te " +
          "parle à voix haute et ta réponse lui est lue dans les branches des lunettes. Réponds " +
          "dans la langue de la question, par défaut en français, en le tutoyant. Sois très bref : " +
          "une ou deux phrases courtes, trente mots au maximum, sauf si on te demande des " +
          "détails ou une explication. Va droit au but, sans formule de politesse ni reformulation. Pas de listes, de mise en " +
          "forme, d'émojis ni de liens ; écris les nombres et unités comme on les prononce. " +
          "Quand on te demande de l'aide sur une tâche manuelle, donne l'étape suivante à faire, " +
          "pas toute la procédure. Tu peux regarder ce que l'utilisateur a devant les yeux avec " +
          "l'outil regarder, mais seulement quand la question l'exige ; dans ce cas, appelle-le " +
          "directement sans rien dire avant. Ne décris pas l'image si on ne te le demande pas. " +
          "Si l'objet de la question n'est pas visible ou pas lisible, dis-le."

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
  private fun journalFile(): File = File(app.filesDir, JOURNAL)

  /** Every line also goes to a file, so the journal survives Android closing the app. */
  private fun appendToJournal(line: String) {
    if (!::app.isInitialized) return
    logWriter.execute {
      runCatching {
        val file = journalFile()
        file.appendText(line + "\n")
        if (file.length() > JOURNAL_MAX_BYTES) {
          val keep = file.readText().takeLast((JOURNAL_MAX_BYTES / 2).toInt())
          file.writeText(keep.substringAfter('\n'))
        }
      }
    }
  }

  /** Called from the crash handler: written at once, since the process is about to die. */
  fun recordCrash(error: Throwable) {
    if (!::app.isInitialized) return
    runCatching {
      journalFile()
          .appendText(
              "${fileClock.format(Date())}  PLANTAGE : ${error}\n" +
                  error.stackTraceToString().take(4000) + "\n",
          )
    }
  }

  /** The end of the journal file, small enough to share as text. */
  fun journalForSharing(): String {
    val body = runCatching { journalFile().readText() }.getOrDefault(logText()).takeLast(100_000)
    return "Lunettes Claude ${_state.value.version}, modèle ${_state.value.model}\n\n$body"
  }

  private var newerRelease: Release? = null
  private var lastUpdateCheck = 0L

  /** Looks for a newer build on GitHub; quietly at most once an hour unless asked. */
  fun checkForUpdate(manual: Boolean) {
    val now = SystemClock.elapsedRealtime()
    if (_state.value.updating) return
    if (!manual && lastUpdateCheck != 0L && now - lastUpdateCheck < 3_600_000) return
    lastUpdateCheck = now
    if (manual) update { it.copy(updateText = "Recherche d'une mise à jour…") }
    scope.launch {
      try {
        val release = Updater.latest()
        val installed = Updater.installedBuild(app)
        if (release.build > installed) {
          newerRelease = release
          update {
            it.copy(
                updateBuild = release.build,
                updateText = "Nouvelle version disponible : 0.1.${release.build}",
            )
          }
          log("Mise à jour disponible : build ${release.build} (installé : $installed)")
        } else {
          newerRelease = null
          update { it.copy(updateBuild = 0, updateText = "Tu as la dernière version.") }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Recherche de mise à jour impossible : ${e.message}")
        if (manual) update { it.copy(updateText = "Recherche impossible : ${e.message}") }
      }
    }
  }

  /** Downloads the newer build and opens Android's installer. */
  fun installUpdate(context: Context) {
    val release = newerRelease ?: return
    if (_state.value.updating) return
    if (!Updater.canInstall(context)) {
      update {
        it.copy(
            updateText =
                "Autorise d'abord Lunettes Claude à installer des applis, puis touche à nouveau " +
                    "Mettre à jour.",
        )
      }
      context.startActivity(
          Intent(
                  android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                  Uri.parse("package:${context.packageName}"),
              )
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      return
    }
    update { it.copy(updating = true, updateText = "Téléchargement…") }
    log("Téléchargement de la mise à jour ${release.build}")
    scope.launch {
      try {
        val apk =
            Updater.download(app, release) { percent ->
              update { it.copy(updateText = "Téléchargement… $percent %") }
            }
        update { it.copy(updating = false, updateText = "Confirme l'installation.") }
        Updater.install(context, apk)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Mise à jour impossible : ${e.message}")
        update { it.copy(updating = false, updateText = "Échec : ${e.message}") }
      }
    }
  }

  /** A question sent without the glasses: checks the key, the budget and the voice. */
  fun testClaude() {
    if (_state.value.testing) return
    val key = SecureStore.get(app, CLAUDE_KEY)
    if (key == null) {
      update { it.copy(testResult = "Aucune clé Claude enregistrée") }
      return
    }
    update { it.copy(testing = true, testResult = "Test en cours…") }
    log("Test de Claude (${_state.value.model})")
    scope.launch {
      val answer = StringBuilder()
      val result =
          try {
            val messages =
                org.json.JSONArray()
                    .put(Claude.textMessage("user", "Dis bonjour et confirme que tu fonctionnes."))
            Claude.stream(
                    key,
                    Claude.buildBody(
                        _state.value.model,
                        "Réponds en une seule phrase courte, en français, en tutoyant.",
                        messages,
                        80,
                    ),
                )
                .collect { event ->
                  when (event) {
                    is ClaudeEvent.Text -> answer.append(event.delta)
                    is ClaudeEvent.ToolUse -> Unit
                    is ClaudeEvent.Done ->
                        recordUsage(event.usage, _state.value.model, countQuestion = false)
                  }
                }
            val text = answer.toString().trim()
            speakOnce(text)
            "Test réussi : $text"
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            "Échec du test : ${e.message}"
          }
      log(result)
      update { it.copy(testing = false, testResult = result) }
    }
  }

  fun setVoice(name: String) {
    update { it.copy(voiceName = name) }
    prefs().edit().putString("tts_voice", name).apply()
  }

  fun setSpeechRate(rate: Float) {
    val clean = rate.coerceIn(0.5f, 2.0f)
    update { it.copy(speechRate = clean) }
    prefs().edit().putFloat("tts_rate", clean).apply()
  }

  /** Lists the phone's voices for the conversation language, local ones first. */
  fun loadVoices() {
    var engine: TextToSpeech? = null
    engine =
        TextToSpeech(app) { status ->
          if (status == TextToSpeech.SUCCESS) {
            val language = Locale.forLanguageTag(speechLanguage()).language
            val found =
                runCatching { engine?.voices.orEmpty() }
                    .getOrDefault(emptySet())
                    .filter { it.locale.language == language }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            val list =
                found.mapIndexed { i, voice ->
                  val where = if (voice.isNetworkConnectionRequired) "internet" else "hors ligne"
                  voice.name to "Voix ${i + 1} · ${voice.locale.displayCountry} · $where"
                }
            update { it.copy(voices = list) }
          }
          scope.launch { runCatching { engine?.shutdown() } }
        }
  }

  /** A sample sentence with the chosen voice and speed. */
  fun previewVoice() {
    speakOnce("Bonjour Axel, voici ma voix à cette vitesse. Elle te convient ?")
  }

  /** Reads a sentence aloud outside a conversation, through whatever audio output is active. */
  private fun speakOnce(text: String) {
    if (text.isEmpty()) return
    var engine: TextToSpeech? = null
    engine =
        TextToSpeech(app) { status ->
          if (status == TextToSpeech.SUCCESS) {
            engine?.language = Locale.forLanguageTag(speechLanguage())
            engine?.setSpeechRate(_state.value.speechRate)
            val wanted = _state.value.voiceName
            if (wanted.isNotEmpty()) {
              runCatching {
                engine?.voices?.firstOrNull { it.name == wanted }?.let { engine?.voice = it }
              }
            }
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
          } else {
            log("Synthèse vocale indisponible (code $status)")
          }
        }
    scope.launch {
      delay(20_000)
      runCatching { engine?.shutdown() }
    }
  }

  fun log(message: String) {
    Log.i(TAG, message)
    appendToJournal("${fileClock.format(Date())}  $message")
    val snapshot =
        synchronized(logLines) {
          logLines.addLast("${clock.format(Date())}  $message")
          while (logLines.size > MAX_LOG) logLines.removeFirst()
          logLines.toList()
        }
    update { it.copy(log = snapshot) }
  }
}
