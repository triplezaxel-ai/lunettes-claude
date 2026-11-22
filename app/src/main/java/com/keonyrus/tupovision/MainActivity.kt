package com.keonyrus.tupovision

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Accent = Color(0xFFD97757)
private val Warning = Color(0xFFFFB454)

class MainActivity : ComponentActivity() {

  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { Engine.refreshEnvironment() }

  // Camera access on the glasses is granted in the Meta AI app, which this launcher opens.
  private val permissionMutex = Mutex()
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val wearablesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
      }

  private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          permissionContinuation = continuation
          continuation.invokeOnCancellation { permissionContinuation = null }
          wearablesPermission.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val state by Engine.state.collectAsStateWithLifecycle()
      MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Screen(
              state = state,
              onGrantPermissions = ::requestAndroidPermissions,
              onInstallMetaAi = ::openMetaAiInStore,
              onRegister = { Engine.register(this) },
              onUnregister = { Engine.unregister(this) },
              onStart = { Engine.start(::requestWearablesPermission) },
              onCopyLog = ::copyLog,
              onNotificationAccess = ::openNotificationAccess,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    Engine.refreshEnvironment()
  }

  private fun requestAndroidPermissions() {
    val wanted = ArrayList<String>()
    wanted.addAll(Engine.REQUIRED_PERMISSIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      wanted.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    androidPermissions.launch(wanted.toTypedArray())
  }

  private fun openMetaAiInStore() {
    val page = "details?id=com.facebook.stella"
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://$page")))
    } catch (e: Exception) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$page")))
    }
  }

  private fun openNotificationAccess() {
    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
  }

  private fun copyLog() {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Journal Lunettes Claude", Engine.logText()))
  }
}

@Composable
private fun Screen(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onStart: () -> Unit,
    onCopyLog: () -> Unit,
    onNotificationAccess: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Lunettes Claude", style = MaterialTheme.typography.headlineSmall)

    val error = state.error
    if (error != null) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(error, color = Warning)
          TextButton(onClick = { Engine.clearError() }) { Text("OK") }
        }
      }
    }

    ConversationSection(state, onStart)
    GlassesSection(state, onGrantPermissions, onInstallMetaAi, onRegister, onUnregister)
    ClaudeSection(state)
    StandbySection(state, onNotificationAccess)
    SettingsSection(state)
    LogSection(state, onCopyLog)
  }
}

@Composable
private fun ConversationSection(state: UiState, onStart: () -> Unit) {
  val ready = state.signedIn && state.registered && state.permissionsGranted
  Section("Conversation") {
    Button(
        onClick = { if (state.running) Engine.stop() else onStart() },
        enabled = state.running || ready,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
      Text(if (state.running) "Arrêter" else "Démarrer la conversation")
    }
    if (!ready && !state.running) {
      Text(
          "Termine d'abord les étapes ci-dessous : autorisations, liaison des lunettes et clé " +
              "API Claude.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Text(state.phase.label, style = MaterialTheme.typography.titleMedium, color = Accent)
    if (state.partial.isNotEmpty()) Text("… ${state.partial}")
    if (state.question.isNotEmpty()) Text("Toi : ${state.question}")
    if (state.answer.isNotEmpty()) Text("Claude : ${state.answer}")
    val latency = state.latencyMs
    if (latency != null) {
      Text(
          "Délai avant la réponse : %.1f s".format(latency / 1000.0),
          style = MaterialTheme.typography.bodySmall,
      )
    }
    val frame = state.frame
    if (frame != null) {
      Text("Ce que Claude a regardé :", style = MaterialTheme.typography.bodySmall)
      Image(
          bitmap = frame.asImageBitmap(),
          contentDescription = "Image de la caméra des lunettes",
          modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
          contentScale = ContentScale.Fit,
      )
    }
    if (state.running) {
      Text(
          "Session : ${state.sessionState} · flux : ${state.streamState} · " +
              "image : ${state.frameInfo}",
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          "Une touche sur la branche met en pause, une seconde reprend.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun GlassesSection(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
) {
  Section("1. Lunettes") {
    StatusLine(state.permissionsGranted, "Autorisations : Bluetooth et micro")
    if (!state.permissionsGranted) {
      OutlinedButton(onClick = onGrantPermissions) { Text("Accorder les autorisations") }
    }
    StatusLine(state.metaInstalled, "App Meta AI installée")
    if (!state.metaInstalled) {
      OutlinedButton(onClick = onInstallMetaAi) { Text("Ouvrir Meta AI dans Google Play") }
    }
    Text(
        "Active d'abord le mode développeur dans Meta AI, lunettes portées et connectées : " +
            "Paramètres → Infos sur l'app, touche cinq fois le numéro de version, active le " +
            "mode développeur, puis Activer. Sur le même écran, touche Installer à côté de " +
            "Device Access Toolkit s'il n'est pas installé. Si Meta AI affiche « Erreur lors de " +
            "l'ouverture du lien », le mode développeur est coupé : désactive-le, réactive-le, " +
            "redémarre Meta AI et recommence la liaison.",
        style = MaterialTheme.typography.bodySmall,
    )
    StatusLine(state.registered, "App liée aux lunettes (${state.registration})")
    if (state.registered) {
      StatusLine(state.hasActiveDevice, "Lunettes connectées (appareils : ${state.deviceCount})")
      TextButton(onClick = onUnregister) { Text("Délier") }
    } else {
      OutlinedButton(onClick = onRegister, enabled = state.permissionsGranted) {
        Text("Lier les lunettes via Meta AI")
      }
    }
  }
}

@Composable
private fun ClaudeSection(state: UiState) {
  Section("2. Claude") {
    StatusLine(state.signedIn, "Clé API Claude enregistrée sur ce téléphone")
    if (state.signedIn) {
      TextButton(onClick = { Engine.clearClaudeKey() }) { Text("Supprimer la clé") }
    } else {
      var draft by remember { mutableStateOf("") }
      OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          label = { Text("Clé de console.anthropic.com (sk-ant-…)") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          // A password field keeps the key out of keyboard suggestions and learning.
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
      )
      Button(
          onClick = {
            Engine.setClaudeKey(draft)
            draft = ""
          },
          enabled = draft.isNotBlank(),
      ) {
        Text("Enregistrer la clé")
      }
      Text(
          "La clé est chiffrée sur ce téléphone et n'est envoyée qu'à api.anthropic.com. " +
              "Fixe aussi un plafond de dépenses mensuel dans la console Anthropic : c'est la " +
              "seule vraie garantie contre les mauvaises surprises.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
      OutlinedButton(onClick = { expanded = true }, enabled = !state.running) {
        Text("Modèle : ${state.model}")
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        state.models.forEach { id ->
          DropdownMenuItem(
              text = { Text(id) },
              onClick = {
                Engine.setModel(id)
                expanded = false
              },
          )
        }
      }
    }
    Text(
        "Haiku : rapide et le moins cher, suffisant pour l'usage courant. Sonnet : plus fin, " +
            "environ trois fois plus cher. Opus : le meilleur, nettement plus coûteux.",
        style = MaterialTheme.typography.bodySmall,
    )
    val budgetText = if (state.monthlyBudget > 0) " sur ${state.monthlyBudget} \$" else ""
    Text(
        "Ce mois-ci : %.2f \$".format(state.monthCost) + budgetText +
            " · ${state.monthQuestions} questions",
    )
    Text(
        "${state.monthInputTokens} jetons envoyés · ${state.monthOutputTokens} reçus. " +
            "Estimation calculée par l'app d'après les tarifs Anthropic.",
        style = MaterialTheme.typography.bodySmall,
    )
    NumberField(
        value = state.monthlyBudget,
        label = "Budget mensuel en dollars (0 = sans limite)",
        onChange = { Engine.setMonthlyBudget(it) },
    )
  }
}

@Composable
private fun StandbySection(state: UiState, onNotificationAccess: () -> Unit) {
  Section("3. Déclenchement depuis les lunettes") {
    SettingSwitch(
        title = "Veille",
        subtitle =
            "Démarre et arrête la conversation depuis les lunettes. Dans Meta AI, attribue " +
                "l'appui long à « Musique » (Spotify). Quand Spotify démarre alors que le " +
                "téléphone est verrouillé ou écran éteint, l'app coupe la musique et lance " +
                "la conversation. « Hey Meta, mets de la musique » devrait avoir le même effet. " +
                "La musique lancée téléphone en main, ou dans la voiture, n'est pas touchée. " +
                "À activer lunettes sur le nez." +
                if (state.standbyAudio.isNotEmpty()) "\nSuit : ${state.standbyAudio}" else "",
        checked = state.standby,
        enabled = true,
        onChange = { Engine.setStandby(it) },
    )
    if (!state.mediaAccess) {
      OutlinedButton(onClick = onNotificationAccess) { Text("Accorder l'accès aux notifications") }
      Text(
          "Android n'indique quelle app joue de la musique qu'avec cet accès. L'app ne lit " +
              "aucune notification. Si l'interrupteur est grisé, ouvre d'abord Paramètres → " +
              "Applications → Lunettes Claude → ⋮ → Autoriser les paramètres restreints.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    OutlinedTextField(
        value = state.triggerPackage,
        onValueChange = { Engine.setTriggerPackage(it.trim()) },
        label = { Text("App de musique déclencheuse (paquet)") },
        supportingText = { Text("Spotify : com.spotify.music. À changer veille coupée.") },
        singleLine = true,
        enabled = !state.standby,
        modifier = Modifier.fillMaxWidth(),
    )
    NumberField(
        value = state.idleSeconds,
        label = "Arrêt automatique après X secondes sans question (0 = jamais)",
        onChange = { Engine.setIdleSeconds(it) },
    )
  }
}

@Composable
private fun SettingsSection(state: UiState) {
  Section("Réglages") {
    Text(
        "À modifier conversation arrêtée ; ils s'appliquent au démarrage suivant.",
        style = MaterialTheme.typography.bodySmall,
    )
    SettingSwitch(
        title = "Micro des lunettes",
        subtitle = "À couper si les lunettes entendent moins bien que le téléphone.",
        checked = state.glassesMic,
        enabled = !state.running,
        onChange = { Engine.setGlassesMic(it) },
    )
    SettingSwitch(
        title = "Photo plutôt qu'image vidéo",
        subtitle =
            "Quand Claude regarde : plus nette pour lire un petit texte ou une étiquette, mais " +
                "plus lente.",
        checked = state.photoMode,
        enabled = !state.running,
        onChange = { Engine.setPhotoMode(it) },
    )
    OutlinedTextField(
        value = state.language,
        onValueChange = { Engine.setLanguage(it.trim()) },
        label = { Text("Langue de la voix") },
        supportingText = { Text("Reconnaissance et synthèse vocale : fr-FR, en-US, ko-KR…") },
        singleLine = true,
        enabled = !state.running,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.wakeWord,
        onValueChange = { Engine.setWakeWord(it) },
        label = { Text("Mot d'appel (facultatif)") },
        supportingText = { Text("Si rempli, seules les phrases contenant ce mot sont envoyées.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun NumberField(value: Int, label: String, onChange: (Int) -> Unit) {
  var draft by remember(value) { mutableStateOf(value.toString()) }
  OutlinedTextField(
      value = draft,
      onValueChange = { text ->
        draft = text.filter { it.isDigit() }.take(6)
        draft.toIntOrNull()?.let(onChange)
      },
      label = { Text(label) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun LogSection(state: UiState, onCopyLog: () -> Unit) {
  Section("Journal") {
    TextButton(onClick = onCopyLog) { Text("Copier le journal") }
    Text(
        state.log.takeLast(60).joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
  Text(
      (if (ok) "✓ " else "○ ") + text,
      color = if (ok) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title)
      Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}
