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
import androidx.camera.video.Quality
import androidx.media3.common.Effect
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

data class ProcessedRecordingResult(
    val primaryUri: Uri?,
    val companionUri: Uri?
)

object DualFormatVideoHelper {
    private const val TAG = "DualFormatVideoHelper"

    private data class VideoMeta(
        val effectiveWidth: Int,
        val effectiveHeight: Int,
        val rotation: Int,
        val isVertical: Boolean
    )

    private fun extractVideoMeta(context: Context, uri: Uri): VideoMeta {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            retriever.release()

            val isVert = when (rot) {
                90, 270 -> true
                0, 180 -> h > w
                else -> false
            }
            val effW = if (rot == 90 || rot == 270) h else w
            val effH = if (rot == 90 || rot == 270) w else h
            VideoMeta(effW, effH, rot, isVert)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading video metadata for $uri", e)
            VideoMeta(1920, 1080, 0, false)
        }
    }

    private fun extractFileMeta(file: File): VideoMeta {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            retriever.release()

            val isVert = when (rot) {
                90, 270 -> true
                0, 180 -> h > w
                else -> false
            }
            val effW = if (rot == 90 || rot == 270) h else w
            val effH = if (rot == 90 || rot == 270) w else h
            VideoMeta(effW, effH, rot, isVert)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading video file metadata for ${file.absolutePath}", e)
            VideoMeta(1920, 1080, 0, false)
        }
    }

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

        val meta = extractVideoMeta(context, uri)
        return if (meta.isVertical) VideoFormatType.VERTICAL else VideoFormatType.HORIZONTAL
    }

    /**
     * Cleanly inserts a video file into MediaStore (Movies/BackgroundRecorder) with IS_PENDING handling.
     */
    fun saveFileToMediaStore(context: Context, file: File, displayName: String): Uri? {
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Cannot save empty or non-existent file: ${file.absolutePath}")
            return null
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackgroundRecorder")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: run {
            Log.e(TAG, "Failed to insert MediaStore record for $displayName")
            return null
        }

        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "Successfully saved video to MediaStore: $uri ($displayName, size=${file.length()})")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing bytes to MediaStore URI: $uri", e)
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
            null
        }
    }

    /**
     * Executes Media3 Transformer to process/scale/crop video with given effects.
     */
    @OptIn(UnstableApi::class)
    suspend fun transformVideo(
        context: Context,
        inputFile: File,
        outputFile: File,
        effectsList: List<Effect>
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            Log.e(TAG, "Input file does not exist or is empty: ${inputFile.absolutePath}")
            return@withContext false
        }
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            val success = suspendCancellableCoroutine<Boolean> { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    try {
                        val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
                        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                            .setEffects(Effects(emptyList(), effectsList))
                            .build()

                        val transformer = Transformer.Builder(context)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    Log.d(TAG, "Media3 export completed: bytes=${exportResult.fileSizeBytes}")
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    Log.e(TAG, "Media3 export error", exportException)
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            })
                            .build()

                        transformer.start(editedMediaItem, outputFile.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed starting Media3 Transformer", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
            success && outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "Exception during transformVideo", e)
            false
        }
    }

    /**
     * Primary entry point for newly completed camera recordings:
     * 1. Inspects raw file dimensions.
     * 2. Conforms resolution to guaranteed 1920×1080 (or 1080×1920) Full HD if camera hardware capped capture at 720p.
     * 3. Generates complementary 1920×1080 companion format if Dual Format is enabled.
     * 4. Saves fresh, finalized entries into MediaStore with zero Scoped Storage overwrite conflicts.
     */
    @OptIn(UnstableApi::class)
    suspend fun processAndSaveRecording(
        context: Context,
        rawFile: File,
        baseName: String,
        isDual: Boolean,
        cropPosition: Float = 0.5f,
        targetQuality: Quality = Quality.FHD
    ): ProcessedRecordingResult = withContext(Dispatchers.IO) {
        if (!rawFile.exists() || rawFile.length() == 0L) {
            Log.e(TAG, "Raw recording file is empty or missing")
            return@withContext ProcessedRecordingResult(null, null)
        }

        val meta = extractFileMeta(rawFile)
        val isVertical = meta.isVertical
        Log.d(TAG, "Raw capture video: effective=${meta.effectiveWidth}x${meta.effectiveHeight}, rot=${meta.rotation}, isVertical=$isVertical")

        // Target primary dimensions: default is guaranteed 1920x1080 FHD (or 1080x1920 vertical)
        val (targetPrimaryW, targetPrimaryH) = when (targetQuality) {
            Quality.UHD -> if (isVertical) Pair(2160, 3840) else Pair(3840, 2160)
            Quality.HD -> if (isVertical) Pair(720, 1280) else Pair(1280, 720)
            Quality.SD -> if (isVertical) Pair(480, 720) else Pair(720, 480)
            else -> if (isVertical) Pair(1080, 1920) else Pair(1920, 1080) // Quality.FHD
        }

        val primaryDisplayName = if (isDual) {
            val suffix = if (isVertical) "_Vertical" else "_Horizontal"
            "${baseName}${suffix}.mp4"
        } else {
            "${baseName}.mp4"
        }

        val maxDim = maxOf(meta.effectiveWidth, meta.effectiveHeight)
        // If user wants FHD (or UHD) and camera HAL capped capture at 720p or lower, conform to exact Full HD
        val needsConform = (targetQuality == Quality.FHD || targetQuality == Quality.UHD) &&
                (meta.effectiveWidth != targetPrimaryW || meta.effectiveHeight != targetPrimaryH)

        var primaryFileToSave = rawFile
        val tempPrimaryConformed = File(context.cacheDir, "fhd_primary_${System.currentTimeMillis()}.mp4")

        if (needsConform) {
            Log.d(TAG, "Upscaling/conforming video from ${meta.effectiveWidth}x${meta.effectiveHeight} to guaranteed Full HD $targetPrimaryW x $targetPrimaryH...")
            val effects = listOf<Effect>(
                Presentation.createForWidthAndHeight(
                    targetPrimaryW,
                    targetPrimaryH,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            )
            val conformed = transformVideo(context, rawFile, tempPrimaryConformed, effects)
            if (conformed && tempPrimaryConformed.exists() && tempPrimaryConformed.length() > 0L) {
                Log.d(TAG, "Successfully conformed video to guaranteed $targetPrimaryW x $targetPrimaryH FHD!")
                primaryFileToSave = tempPrimaryConformed
            } else {
                Log.w(TAG, "Primary conform failed; saving raw recording file")
                primaryFileToSave = rawFile
            }
        }

        val primaryUri = saveFileToMediaStore(context, primaryFileToSave, primaryDisplayName)

        // Generate companion video if Dual Format is enabled
        var companionUri: Uri? = null
        if (isDual) {
            val companionIsVertical = !isVertical
            val (companionTargetW, companionTargetH) = if (companionIsVertical) {
                Pair(1080, 1920)
            } else {
                Pair(1920, 1080)
            }

            val companionSuffix = if (companionIsVertical) "_Vertical" else "_Horizontal"
            val companionDisplayName = "${baseName}${companionSuffix}.mp4"

            val tempCompanion = File(context.cacheDir, "companion_${System.currentTimeMillis()}.mp4")
            val companionEffects = mutableListOf<Effect>()

            if (!companionIsVertical) {
                // Source is vertical -> create horizontal widescreen 1920x1080
                if (cropPosition <= 0.3f) {
                    companionEffects.add(Crop(-1f, 1f, 0.2f, 0.8328f))
                } else if (cropPosition >= 0.7f) {
                    companionEffects.add(Crop(-1f, 1f, -0.8328f, -0.2f))
                }
                companionEffects.add(
                    Presentation.createForWidthAndHeight(
                        1920,
                        1080,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    )
                )
            } else {
                // Source is horizontal -> create vertical portrait 1080x1920
                companionEffects.add(
                    Presentation.createForWidthAndHeight(
                        1080,
                        1920,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    )
                )
            }

            val companionSuccess = transformVideo(context, primaryFileToSave, tempCompanion, companionEffects)
            if (companionSuccess && tempCompanion.exists() && tempCompanion.length() > 0L) {
                companionUri = saveFileToMediaStore(context, tempCompanion, companionDisplayName)
                Log.d(TAG, "Successfully created and saved companion video: $companionUri ($companionDisplayName)")
            } else {
                Log.w(TAG, "Failed creating companion video")
            }

            if (tempCompanion.exists()) tempCompanion.delete()
        }

        if (tempPrimaryConformed.exists()) tempPrimaryConformed.delete()

        ProcessedRecordingResult(primaryUri, companionUri)
    }

    /**
     * Given an existing video URI (e.g. from RecordingsScreen), creates a genuine complementary video format:
     * - 9:16 Vertical -> Real 16:9 Horizontal widescreen crop (1920x1080)
     * - 16:9 Horizontal -> Real 9:16 Vertical portrait crop (1080x1920)
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
        val tempInput = File(context.cacheDir, "comp_in_${System.currentTimeMillis()}.mp4")
        val tempOutput = File(context.cacheDir, "comp_out_${System.currentTimeMillis()}.mp4")

        try {
            // Copy source video from MediaStore URI to local temp file
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempInput.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Unable to read source video URI: $sourceUri")
                return@withContext null
            }

            val effectsList = mutableListOf<Effect>()
            if (targetFormat == VideoFormatType.HORIZONTAL) {
                if (cropPosition <= 0.3f) {
                    effectsList.add(Crop(-1f, 1f, 0.2f, 0.8328f))
                } else if (cropPosition >= 0.7f) {
                    effectsList.add(Crop(-1f, 1f, -0.8328f, -0.2f))
                }
                effectsList.add(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            } else {
                effectsList.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }

            val transformed = transformVideo(context, tempInput, tempOutput, effectsList)
            if (!transformed || !tempOutput.exists() || tempOutput.length() == 0L) {
                Log.e(TAG, "Companion transformation failed")
                return@withContext null
            }

            saveFileToMediaStore(context, tempOutput, companionDisplayName)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating companion format", e)
            null
        } finally {
            if (tempInput.exists()) tempInput.delete()
            if (tempOutput.exists()) tempOutput.delete()
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

    /**
     * Backward-compatible helper for legacy callers.
     */
    @OptIn(UnstableApi::class)
    suspend fun ensureFullHdResolution(
        context: Context,
        sourceUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        val meta = extractVideoMeta(context, sourceUri)
        val maxDim = maxOf(meta.effectiveWidth, meta.effectiveHeight)
        if (maxDim >= 1800) {
            return@withContext true
        }

        val targetW = if (meta.isVertical) 1080 else 1920
        val targetH = if (meta.isVertical) 1920 else 1080

        val tempIn = File(context.cacheDir, "fhd_in_${System.currentTimeMillis()}.mp4")
        val tempOut = File(context.cacheDir, "fhd_out_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempIn.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            val effects = listOf<Effect>(
                Presentation.createForWidthAndHeight(targetW, targetH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            )
            val success = transformVideo(context, tempIn, tempOut, effects)
            if (success && tempOut.exists() && tempOut.length() > 0L) {
                // If possible, overwrite or copy
                try {
                    context.contentResolver.openOutputStream(sourceUri, "rwt")?.use { out ->
                        tempOut.inputStream().use { input -> input.copyTo(out) }
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            if (tempIn.exists()) tempIn.delete()
            if (tempOut.exists()) tempOut.delete()
        }
    }
}
