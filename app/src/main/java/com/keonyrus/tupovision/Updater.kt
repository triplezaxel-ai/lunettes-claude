package com.keonyrus.tupovision

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer build published on GitHub. */
data class Release(val build: Int, val apkUrl: String)

/**
 * Updates from the repository's "latest" release. Every build is signed with the same key, so the
 * downloaded APK installs over this one and keeps the settings, the Claude key and Meta's grants.
 */
object Updater {
  private const val LATEST =
      "https://api.github.com/repos/triplezaxel-ai/lunettes-claude/releases/latest"
  private const val APK_NAME = "lunettes-claude.apk"

  /** This build's number: the GitHub run that produced it. */
  fun installedBuild(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
  }

  /** The published release, or an exception that says why it could not be read. */
  suspend fun latest(): Release =
      withContext(Dispatchers.IO) {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("accept", "application/vnd.github+json")
        try {
          val status = connection.responseCode
          if (status == 404) {
            throw IllegalStateException(
                "dépôt introuvable : il doit être public pour les mises à jour depuis l'app",
            )
          }
          if (status !in 200..299) throw IllegalStateException("GitHub a répondu HTTP $status")
          val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
          // The release title is "Lunettes Claude build N", N being the GitHub run number.
          val build =
              Regex("build (\\d+)").find(json.optString("name"))?.groupValues?.get(1)?.toInt()
                  ?: throw IllegalStateException("numéro de version illisible")
          val assets = json.optJSONArray("assets")
          var url: String? = null
          if (assets != null) {
            for (i in 0 until assets.length()) {
              val asset = assets.getJSONObject(i)
              if (asset.optString("name") == APK_NAME) url = asset.optString("browser_download_url")
            }
          }
          Release(build, url ?: throw IllegalStateException("APK absent de la release"))
        } finally {
          connection.disconnect()
        }
      }

  /** Downloads the APK into the app's cache, reporting progress from 0 to 100. */
  suspend fun download(context: Context, release: Release, progress: (Int) -> Unit): File =
      withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "lunettes-claude-${release.build}.apk")
        var url = URL(release.apkUrl)
        var connection: HttpURLConnection
        // GitHub answers with a redirect to its file storage; follow it by hand to be safe.
        var hops = 0
        while (true) {
          connection = url.openConnection() as HttpURLConnection
          connection.instanceFollowRedirects = false
          connection.connectTimeout = 15000
          connection.readTimeout = 60000
          val status = connection.responseCode
          if (status in 300..399 && hops < 5) {
            url = URL(url, connection.getHeaderField("Location"))
            connection.disconnect()
            hops += 1
            continue
          }
          if (status !in 200..299) throw IllegalStateException("téléchargement : HTTP $status")
          break
        }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
          file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var last = -1
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              done += read
              if (total > 0) {
                val percent = (done * 100 / total).toInt()
                if (percent != last) {
                  last = percent
                  withContext(Dispatchers.Main) { progress(percent) }
                }
              }
            }
          }
        }
        connection.disconnect()
        file
      }

  /** Hands the APK to Android's installer, which asks for confirmation. */
  fun install(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }

  /** Android must allow this app to install packages; false sends the user to that setting. */
  fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()
}
