package com.example.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

enum class VideoFormatType(val label: String, val badge: String, val ratio: String) {
    VERTICAL("Vertical", "📱 9:16 Vertical", "9:16"),
    HORIZONTAL("Horizontal", "🖥️ 16:9 Horizontal", "16:9"),
    UNKNOWN("Standard", "🎬 Standard", "Auto")
}

object DualFormatVideoHelper {
    private const val TAG = "DualFormatVideoHelper"

    /**
     * Determines whether a video is vertical or horizontal.
     * Checks filename suffix first for instant resolution, falling back to MediaMetadataRetriever.
     */
    fun detectVideoFormat(context: Context, uri: Uri, displayName: String? = null): VideoFormatType {
        val name = displayName ?: ""
        if (name.contains("Vertical", ignoreCase = true) || name.endsWith("_V.mp4", ignoreCase = true)) {
            return VideoFormatType.VERTICAL
        }
        if (name.contains("Horizontal", ignoreCase = true) || name.endsWith("_H.mp4", ignoreCase = true)) {
            return VideoFormatType.HORIZONTAL
        }

        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            retriever.release()

            val rotation = rotationStr?.toIntOrNull() ?: 0
            val width = widthStr?.toIntOrNull() ?: 1920
            val height = heightStr?.toIntOrNull() ?: 1080

            val isVertical = when (rotation) {
                90, 270 -> true
                0, 180 -> height > width
                else -> false
            }

            if (isVertical) VideoFormatType.VERTICAL else VideoFormatType.HORIZONTAL
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve video metadata for format detection", e)
            VideoFormatType.UNKNOWN
        }
    }

    /**
     * Given an existing video URI, creates a genuine complementary video format:
     * - 9:16 Vertical -> Real 16:9 Horizontal widescreen crop (upright, correctly framed, no flipping)
     * - 16:9 Horizontal -> Real 9:16 Vertical portrait crop
     * Powered by hardware-accelerated Media3 Transformer.
     */
    @OptIn(UnstableApi::class)
    suspend fun generateCompanionFormat(
        context: Context,
        sourceUri: Uri,
        originalDisplayName: String,
        cropPosition: Float = 0.5f
    ): Uri? = withContext(Dispatchers.IO) {
        val primaryFormat = detectVideoFormat(context, sourceUri, originalDisplayName)
        val targetFormat = if (primaryFormat == VideoFormatType.VERTICAL) {
            VideoFormatType.HORIZONTAL
        } else {
            VideoFormatType.VERTICAL
        }

        val companionDisplayName = buildCompanionName(originalDisplayName, targetFormat)
        Log.d(TAG, "Generating real companion format: primary=$primaryFormat, target=$targetFormat, name=$companionDisplayName")

        val tempFile = File(context.cacheDir, "real_dual_${System.currentTimeMillis()}.mp4")

        try {
            val targetAspectRatio = if (targetFormat == VideoFormatType.HORIZONTAL) (16f / 9f) else (9f / 16f)

            // Select transformation effects based on target format and user's crop framing
            val effectsList = mutableListOf<androidx.media3.common.Effect>()
            if (targetFormat == VideoFormatType.HORIZONTAL) {
                if (cropPosition <= 0.3f) {
                    // Top crop framing (shift window toward top of 9:16 frame)
                    effectsList.add(Crop(-1f, 1f, 0.2f, 0.8328f))
                    effectsList.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT))
                } else if (cropPosition >= 0.7f) {
                    // Bottom crop framing (shift window toward bottom of 9:16 frame)
                    effectsList.add(Crop(-1f, 1f, -0.8328f, -0.2f))
                    effectsList.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT))
                } else {
                    // Center crop framing
                    effectsList.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                }
            } else {
                // Vertical crop from horizontal source
                effectsList.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }

            val success = suspendCancellableCoroutine<Boolean> { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    try {
                        val mediaItem = MediaItem.fromUri(sourceUri)
                        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                            .setEffects(
                                Effects(
                                    /* audioProcessors = */ emptyList(),
                                    /* videoEffects = */ effectsList
                                )
                            )
                            .build()

                        val transformer = Transformer.Builder(context)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    Log.d(TAG, "Media3 Transformation completed successfully: size=${exportResult.fileSizeBytes}")
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    Log.e(TAG, "Media3 Transformation failed", exportException)
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            })
                            .build()

                        transformer.start(editedMediaItem, tempFile.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initiate Media3 Transformer", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }

            if (!success || !tempFile.exists() || tempFile.length() == 0L) {
                Log.e(TAG, "Companion generation was unsuccessful, output file missing or empty")
                return@withContext null
            }

            // Save the real companion video file into MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, companionDisplayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackgroundRecorder")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val insertedUri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext null

            context.contentResolver.openOutputStream(insertedUri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(insertedUri, contentValues, null, null)
            }

            Log.d(TAG, "Successfully created real companion video: $insertedUri ($companionDisplayName)")
            insertedUri
        } catch (e: Exception) {
            Log.e(TAG, "Error generating real companion format", e)
            null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun buildCompanionName(originalName: String, targetFormat: VideoFormatType): String {
        val baseWithoutExt = originalName.removeSuffix(".mp4").removeSuffix(".MP4")
        val cleanBase = baseWithoutExt
            .replace("_Vertical", "")
            .replace("_Horizontal", "")
            .replace("_V", "")
            .replace("_H", "")

        val suffix = if (targetFormat == VideoFormatType.HORIZONTAL) "_Horizontal" else "_Vertical"
        return "${cleanBase}${suffix}.mp4"
    }

    fun getCleanBaseName(displayName: String): String {
        return displayName.removeSuffix(".mp4").removeSuffix(".MP4")
            .replace("_Vertical", "")
            .replace("_Horizontal", "")
            .replace("_V", "")
            .replace("_H", "")
    }
}
