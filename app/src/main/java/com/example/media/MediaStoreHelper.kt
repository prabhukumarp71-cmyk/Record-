package com.example.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaStoreHelper {
    suspend fun getVideos(context: Context): List<VideoMedia> = withContext(Dispatchers.IO) {
        val rawVideos = mutableListOf<VideoMedia>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Movies/BackgroundRecorder%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val date = cursor.getLong(dateColumn)
                    var width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
                    var height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    val format = DualFormatVideoHelper.detectVideoFormat(context, uri, name)

                    if (width <= 0 || height <= 0) {
                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(context, uri)
                            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            retriever.release()
                            width = w
                            height = h
                        } catch (_: Exception) {}
                    }

                    rawVideos.add(
                        VideoMedia(
                            uri = uri,
                            name = name,
                            duration = duration,
                            size = size,
                            dateAdded = date,
                            formatType = format,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        val baseGroups = rawVideos.groupBy { DualFormatVideoHelper.getCleanBaseName(it.name) }

        rawVideos.map { item ->
            val cleanBase = DualFormatVideoHelper.getCleanBaseName(item.name)
            val group = baseGroups[cleanBase] ?: emptyList()
            val companion = group.firstOrNull { it.uri != item.uri && it.formatType != item.formatType }
            if (companion != null) {
                item.copy(companionUri = companion.uri)
            } else {
                item
            }
        }
    }
}
