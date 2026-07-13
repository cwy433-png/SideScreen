package com.sidescreen.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Owns ambient media lifecycle (photo rotation jobs, bitmap crossfade, ExoPlayer).
 * MainActivity decides *which* effective mode to show; this class applies/stops it.
 */
class AmbientDisplayController(
    private val activity: AppCompatActivity,
    private val clockView: View,
    private val imageContainer: View,
    private val imageA: ImageView,
    private val imageB: ImageView,
    private val playerView: PlayerView,
    private val contentResolver: ContentResolver,
) {
    companion object {
        private const val TAG = "AmbientDisplay"
        private const val PHOTO_INTERVAL_MS = 30_000L
        private const val CROSSFADE_MS = 500L
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var photoJob: Job? = null
    private var rotateRunnable: Runnable? = null
    private var exoPlayer: ExoPlayer? = null

    private var photoUris: List<Uri> = emptyList()
    private var photoIndex = 0
    private var showingA = true
    private var appliedMode: AmbientMode? = null

    /**
     * Bumped on every [apply]/[stopAll] so late IO / player callbacks from a
     * previous session cannot mutate views after a mode switch or stop.
     */
    private var mediaSession: Long = 0L

    /** Invoked on main thread when PHOTOS/VIDEO cannot run and clock is shown instead. */
    var onFallbackToClock: ((reason: String) -> Unit)? = null

    /**
     * Apply ambient content for [mode]. Safe to call repeatedly; previous media is
     * torn down before starting the new mode.
     *
     * When Mac-synced assets are present they take priority over SAF sources;
     * [ambientMode] itself is never changed by sync — only available assets.
     */
    fun apply(
        mode: AmbientMode,
        photoTreeUri: String?,
        videoUri: String?,
        syncedPhotoUris: List<Uri> = emptyList(),
        syncedVideoUri: Uri? = null,
    ) {
        if (mode == appliedMode && mode != AmbientMode.PHOTOS && mode != AmbientMode.VIDEO) {
            // BLACK/CLOCK have no media to refresh; still ensure visibility.
            showOnly(mode)
            return
        }

        mediaSession++
        val session = mediaSession
        stopMediaOnly()
        appliedMode = mode

        when (mode) {
            AmbientMode.BLACK -> showOnly(AmbientMode.BLACK)
            AmbientMode.CLOCK -> showOnly(AmbientMode.CLOCK)
            AmbientMode.PHOTOS -> startPhotos(syncedPhotoUris, photoTreeUri, session)
            AmbientMode.VIDEO -> {
                val effectiveVideo = syncedVideoUri?.toString() ?: videoUri
                startVideo(effectiveVideo, session)
            }
        }
    }

    /** Cancel photo work, release ExoPlayer, hide content children. */
    fun stopAll() {
        mediaSession++
        stopMediaOnly()
        appliedMode = null
        showOnly(null)
    }

    private fun stopMediaOnly() {
        cancelPhotoWork()
        releasePlayerSync()
        imageA.animate().cancel()
        imageB.animate().cancel()
        imageA.setImageDrawable(null)
        imageB.setImageDrawable(null)
        imageA.alpha = 0f
        imageB.alpha = 0f
        showingA = true
        photoUris = emptyList()
        photoIndex = 0
    }

    private fun isCurrentSession(session: Long): Boolean = session == mediaSession

    private fun showOnly(mode: AmbientMode?) {
        clockView.visibility = if (mode == AmbientMode.CLOCK) View.VISIBLE else View.GONE
        imageContainer.visibility = if (mode == AmbientMode.PHOTOS) View.VISIBLE else View.GONE
        playerView.visibility = if (mode == AmbientMode.VIDEO) View.VISIBLE else View.GONE
        // BLACK or null → all children gone (container background remains black).
    }

    // region Photos

    private fun startPhotos(
        syncedPhotoUris: List<Uri>,
        treeUriString: String?,
        session: Long,
    ) {
        showOnly(AmbientMode.PHOTOS)
        photoJob =
            activity.lifecycleScope.launch {
                val uris =
                    withContext(Dispatchers.IO) {
                        if (syncedPhotoUris.isNotEmpty()) {
                            syncedPhotoUris
                        } else {
                            if (treeUriString.isNullOrBlank()) {
                                emptyList()
                            } else {
                                val treeUri =
                                    try {
                                        Uri.parse(treeUriString)
                                    } catch (_: Exception) {
                                        null
                                    }
                                if (treeUri == null) {
                                    emptyList()
                                } else {
                                    enumeratePhotos(treeUri)
                                }
                            }
                        }
                    }
                if (!isActive || !isCurrentSession(session)) return@launch
                if (uris.isEmpty()) {
                    if (syncedPhotoUris.isEmpty() && treeUriString.isNullOrBlank()) {
                        fallbackClock("No photo folder selected")
                    } else {
                        fallbackClock("Photo folder empty or inaccessible")
                    }
                    return@launch
                }
                photoUris = uris
                photoIndex = 0
                val first =
                    withContext(Dispatchers.IO) {
                        decodeSampledBitmap(uris[0])
                    }
                if (!isActive || !isCurrentSession(session)) return@launch
                if (first == null) {
                    fallbackClock("Failed to decode photos")
                    return@launch
                }
                imageA.setImageBitmap(first)
                imageA.alpha = 1f
                imageB.alpha = 0f
                showingA = true
                scheduleNextPhoto(session)
            }
    }

    private fun scheduleNextPhoto(session: Long) {
        cancelRotateCallback()
        if (!isCurrentSession(session) || photoUris.size < 2) {
            // Single image or stale session: keep showing, no rotation needed.
            return
        }
        rotateRunnable =
            Runnable {
                if (!isCurrentSession(session)) return@Runnable
                photoJob =
                    activity.lifecycleScope.launch {
                        if (!isCurrentSession(session) || photoUris.isEmpty()) return@launch
                        val nextIndex = (photoIndex + 1) % photoUris.size
                        val uri = photoUris[nextIndex]
                        val bitmap =
                            withContext(Dispatchers.IO) {
                                decodeSampledBitmap(uri)
                            }
                        if (!isActive || !isCurrentSession(session)) return@launch
                        if (bitmap == null) {
                            // Skip bad frame; try again later.
                            scheduleNextPhoto(session)
                            return@launch
                        }
                        photoIndex = nextIndex
                        crossfadeTo(bitmap)
                        scheduleNextPhoto(session)
                    }
            }
        mainHandler.postDelayed(rotateRunnable!!, PHOTO_INTERVAL_MS)
    }

    private fun crossfadeTo(bitmap: Bitmap) {
        val incoming = if (showingA) imageB else imageA
        val outgoing = if (showingA) imageA else imageB
        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming
            .animate()
            .alpha(1f)
            .setDuration(CROSSFADE_MS)
            .start()
        outgoing
            .animate()
            .alpha(0f)
            .setDuration(CROSSFADE_MS)
            .withEndAction {
                outgoing.setImageDrawable(null)
            }.start()
        showingA = !showingA
    }

    private fun enumeratePhotos(treeUri: Uri): List<Uri> {
        return try {
            val tree = DocumentFile.fromTreeUri(activity, treeUri) ?: return emptyList()
            if (!tree.canRead()) return emptyList()
            tree
                .listFiles()
                .asSequence()
                .filter { it.isFile && it.canRead() }
                .filter { file ->
                    val name = file.name?.lowercase() ?: return@filter false
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
                    ext in IMAGE_EXTENSIONS
                }.mapNotNull { it.uri }
                .toList()
                .sortedBy { it.toString() }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        return try {
            val metrics = displayMetrics()
            val reqW = metrics.widthPixels.coerceAtLeast(1)
            val reqH = metrics.heightPixels.coerceAtLeast(1)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val localFile =
                if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == "file") {
                    uri.path?.let(::File)
                } else {
                    null
                }
            if (localFile != null) {
                if (!localFile.isFile || !localFile.canRead()) {
                    Log.w(TAG, "Synced photo is not readable: ${localFile.absolutePath}")
                    return null
                }
                BitmapFactory.decodeFile(localFile.absolutePath, bounds)
            } else {
                openUriInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                } ?: return null
            }

            val outW = bounds.outWidth
            val outH = bounds.outHeight
            if (outW <= 0 || outH <= 0) {
                Log.w(TAG, "Could not read photo bounds: uri=$uri mime=${bounds.outMimeType}")
                return null
            }

            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(outW, outH, reqW, reqH)
                    inJustDecodeBounds = false
                }
            val decoded =
                if (localFile != null) {
                    BitmapFactory.decodeFile(localFile.absolutePath, opts)
                } else {
                    openUriInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }
            if (decoded == null) {
                Log.w(
                    TAG,
                    "Photo decode returned null: uri=$uri ${outW}x$outH sample=${opts.inSampleSize}",
                )
            }
            decoded
        } catch (_: SecurityException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Photo decode failed: uri=$uri", e)
            null
        } catch (_: OutOfMemoryError) {
            // Not an Exception; catch at decode boundary so callers can skip/fallback.
            Log.w(TAG, "Photo decode ran out of memory: uri=$uri")
            null
        }
    }

    /** Prefer FileInputStream for app-private file:// URIs from Mac sync. */
    private fun openUriInputStream(uri: Uri): InputStream? {
        return try {
            if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == "file") {
                val path = uri.path ?: return null
                FileInputStream(File(path))
            } else {
                contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = activity.getSystemService(AppCompatActivity.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /**
     * Power-of-two sampling that fits [reqWidth]x[reqHeight] and also respects a
     * screen-pixel budget so extreme aspect ratios cannot retain huge pixel counts.
     */
    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 1

        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        // Pixel budget: decoded pixels must not exceed full-screen pixel count.
        val maxPixels = reqWidth.toLong() * reqHeight.toLong()
        while (inSampleSize < (1 shl 30)) {
            val sampledW = (width / inSampleSize).coerceAtLeast(1)
            val sampledH = (height / inSampleSize).coerceAtLeast(1)
            if (sampledW.toLong() * sampledH.toLong() <= maxPixels) break
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun cancelPhotoWork() {
        photoJob?.cancel()
        photoJob = null
        cancelRotateCallback()
    }

    private fun cancelRotateCallback() {
        rotateRunnable?.let { mainHandler.removeCallbacks(it) }
        rotateRunnable = null
    }

    // endregion

    // region Video

    private fun startVideo(videoUriString: String?, session: Long) {
        if (videoUriString.isNullOrBlank()) {
            fallbackClock("No video selected")
            return
        }
        val uri =
            try {
                Uri.parse(videoUriString)
            } catch (_: Exception) {
                fallbackClock("Invalid video URI")
                return
            }

        showOnly(AmbientMode.VIDEO)
        try {
            releasePlayerSync()
            val player =
                ExoPlayer.Builder(activity).build().also { p ->
                    p.repeatMode = Player.REPEAT_MODE_ONE
                    p.volume = 0f
                    p.setMediaItem(MediaItem.fromUri(uri))
                    p.addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                mainHandler.post {
                                    // Ignore errors from released / superseded players.
                                    if (!isCurrentSession(session)) return@post
                                    if (appliedMode != AmbientMode.VIDEO) return@post
                                    if (exoPlayer !== p) return@post
                                    fallbackClock("Video playback error: ${error.errorCodeName}")
                                }
                            }
                        },
                    )
                    p.prepare()
                    p.playWhenReady = true
                }
            if (!isCurrentSession(session)) {
                // apply/stop raced while we built the player; release and exit.
                try {
                    player.release()
                } catch (_: Exception) {
                    // ignore
                }
                return
            }
            exoPlayer = player
            playerView.player = player
            playerView.useController = false
            playerView.keepScreenOn = false
            playerView.isClickable = false
            playerView.isFocusable = false
        } catch (e: Exception) {
            if (!isCurrentSession(session)) {
                releasePlayerSync()
                return
            }
            releasePlayerSync()
            fallbackClock("Video failed: ${e.message}")
        }
    }

    /** Release ExoPlayer synchronously on the calling thread (expected: main). */
    private fun releasePlayerSync() {
        playerView.player = null
        val player = exoPlayer
        exoPlayer = null
        if (player != null) {
            try {
                player.playWhenReady = false
                player.stop()
            } catch (_: Exception) {
                // ignore
            }
            try {
                player.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    // endregion

    private fun fallbackClock(reason: String) {
        stopMediaOnly()
        appliedMode = AmbientMode.CLOCK
        showOnly(AmbientMode.CLOCK)
        onFallbackToClock?.invoke(reason)
    }
}
