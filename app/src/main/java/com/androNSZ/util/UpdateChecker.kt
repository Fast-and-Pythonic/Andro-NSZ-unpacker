package com.androNSZ.util

import com.androNSZ.model.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer app version and downloads the update APK.
 *
 * The whole feature is built on the JDK's [HttpURLConnection] + [JSONObject]
 * (both bundled with Android) so it adds no networking dependency. Blocking IO is
 * confined to [Dispatchers.IO].
 */
object UpdateChecker {

   private const val LATEST_RELEASE_URL =
      "https://api.github.com/repos/Fast-and-Pythonic/Andro-NSZ-unpacker/releases/latest"

   // GitHub's REST API rejects requests without a User-Agent with HTTP 403.
   private const val USER_AGENT = "AndroNSZ-UpdateChecker"

   private const val CONNECT_TIMEOUT_MS = 10_000
   private const val READ_TIMEOUT_MS = 10_000

   /**
    * Fetch the latest published release. Throws on network/HTTP/parse errors —
    * the caller maps failures to [com.androNSZ.model.UpdateState.Failed].
    */
   suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
      val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
         requestMethod = "GET"
         connectTimeout = CONNECT_TIMEOUT_MS
         readTimeout = READ_TIMEOUT_MS
         setRequestProperty("User-Agent", USER_AGENT)
         setRequestProperty("Accept", "application/vnd.github+json")
      }
      try {
         val code = conn.responseCode
         if (code != HttpURLConnection.HTTP_OK) {
            throw java.io.IOException("GitHub API returned HTTP $code")
         }
         val body = conn.inputStream.bufferedReader().use { it.readText() }
         parseRelease(JSONObject(body))
      } finally {
         conn.disconnect()
      }
   }

   private fun parseRelease(json: JSONObject): ReleaseInfo {
      val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
      val versionName = tag.removePrefix("v").removePrefix("V").trim()
      val pageUrl = json.optString("html_url")

      // Prefer the attached .apk asset; fall back to the release page.
      var apkUrl = pageUrl
      val assets = json.optJSONArray("assets")
      if (assets != null) {
         for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
               apkUrl = asset.optString("browser_download_url", pageUrl)
               break
            }
         }
      }

      return ReleaseInfo(
         versionName = versionName,
         apkUrl = apkUrl,
         releasePageUrl = pageUrl,
         notes = json.optString("body").trim()
      )
   }

   /**
    * Compare dotted version names numerically (so "1.10" > "1.9"). Returns true
    * when [latest] is strictly newer than [current]. Non-numeric or missing
    * segments are treated as 0, so it degrades gracefully on odd tags.
    */
   fun isNewer(latest: String, current: String): Boolean {
      val a = latest.split(".")
      val b = current.split(".")
      val n = maxOf(a.size, b.size)
      for (i in 0 until n) {
         val x = a.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
         val y = b.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
         if (x != y) return x > y
      }
      return false
   }

   /**
    * Download [url] into [dest], reporting fractional progress via [onProgress]
    * (0f..1f, or -1f while the total size is unknown). Overwrites [dest].
    */
   suspend fun downloadApk(
      url: String,
      dest: File,
      onProgress: (Float) -> Unit
   ): Unit = withContext(Dispatchers.IO) {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
         requestMethod = "GET"
         connectTimeout = CONNECT_TIMEOUT_MS
         readTimeout = READ_TIMEOUT_MS
         setRequestProperty("User-Agent", USER_AGENT)
         instanceFollowRedirects = true
      }
      try {
         val code = conn.responseCode
         if (code != HttpURLConnection.HTTP_OK) {
            throw java.io.IOException("Download failed: HTTP $code")
         }
         val total = conn.contentLength.toLong()
         conn.inputStream.use { input ->
            dest.outputStream().use { output ->
               val buffer = ByteArray(64 * 1024)
               var downloaded = 0L
               while (true) {
                  val read = input.read(buffer)
                  if (read < 0) break
                  output.write(buffer, 0, read)
                  downloaded += read
                  onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
               }
               output.flush()
            }
         }
      } finally {
         conn.disconnect()
      }
   }
}
