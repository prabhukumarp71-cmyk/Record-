package com.example.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

enum class VideoFormatType(val label: String, val badge: String, val ratio: String) {
    VERTICAL("Vertical", "📱 Vertical", "9:16"),
    HORIZONTAL("Horizontal", "🖥️ Horizontal", "16:9"),
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
     * Given an existing video URI, creates the complementary format (Vertical <-> Horizontal)
     * using MediaExtractor and MediaMuxer with orientation transformation.
     * This operation performs lossless track copying without re-encoding, finishing in milliseconds.
     */
    suspend fun generateCompanionFormat(
        context: Context,
        sourceUri: Uri,
        originalDisplayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val primaryFormat = detectVideoFormat(context, sourceUri, originalDisplayName)
        val targetFormat = if (primaryFormat == VideoFormatType.VERTICAL) {
            VideoFormatType.HORIZONTAL
        } else {
            VideoFormatType.VERTICAL
        }

        // Target orientation hint for MP4 header:
        // 0 degrees = Horizontal (16:9 Landscape)
        // 90 degrees = Vertical (9:16 Portrait)
        val targetRotation = if (targetFormat == VideoFormatType.HORIZONTAL) 0 else 90

        val companionDisplayName = buildCompanionName(originalDisplayName, targetFormat)

        Log.d(TAG, "Generating companion format: target=$targetFormat, rotation=$targetRotation, name=$companionDisplayName")

        val tempFile = File(context.cacheDir, "dual_format_${System.currentTimeMillis()}.mp4")
        try {
            val extractor = MediaExtractor()
            val afd = context.contentResolver.openAssetFileDescriptor(sourceUri, "r")
            if (afd != null) {
                afd.use { assetFd ->
                    extractor.setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                }
            } else {
                val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return@withContext null
                pfd.use { parcelFd ->
                    extractor.setDataSource(parcelFd.fileDescriptor)
                }
            }

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(targetRotation)

            val trackCount = extractor.trackCount
            val trackIndexMap = HashMap<Int, Int>()
            var maxBufferSize = 1024 * 1024 // 1MB fallback

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val newTrackIndex = muxer.addTrack(format)
                    trackIndexMap[i] = newTrackIndex

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > maxBufferSize) {
                            maxBufferSize = size
                        }
                    }
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) break

                val muxerTrackIndex = trackIndexMap[sampleTrackIndex]
                if (muxerTrackIndex != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            // Save the resulting file to MediaStore
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

            Log.d(TAG, "Successfully created companion format: $insertedUri ($companionDisplayName)")
            insertedUri
        } catch (e: Exception) {
            Log.e(TAG, "Error generating companion format", e)
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
