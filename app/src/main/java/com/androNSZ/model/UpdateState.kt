package com.androNSZ.model

/**
 * A release published on GitHub, distilled to what the in-app updater needs.
 *
 * @param versionName the release tag without a leading "v" (e.g. "1.2").
 * @param apkUrl direct download URL of the release's `.apk` asset, or the release
 *   page URL as a fallback when no APK asset is attached.
 * @param releasePageUrl the human-facing release page on GitHub.
 * @param notes the release body (changelog); may be empty.
 */
data class ReleaseInfo(
   val versionName: String,
   val apkUrl: String,
   val releasePageUrl: String,
   val notes: String
)

/**
 * State of the update-check flow, surfaced to the UI as a Compose state value on
 * [com.androNSZ.viewmodel.MainViewModel].
 */
sealed class UpdateState {
   /** Nothing to show (no check running, no result). */
   object Idle : UpdateState()

   /** A check is in flight. */
   object Checking : UpdateState()

   /** The installed version is the latest (shown only for a manual check). */
   object UpToDate : UpdateState()

   /** A newer release is available. */
   data class Available(val release: ReleaseInfo) : UpdateState()

   /** The update APK is downloading; [progress] is 0f..1f (or negative if unknown). */
   data class Downloading(val progress: Float) : UpdateState()

   /** The check or download failed; [reason] is a short human-readable message. */
   data class Failed(val reason: String) : UpdateState()
}
