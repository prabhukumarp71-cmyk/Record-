package com.example.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoMedia(
    val uri: android.net.Uri,
    val name: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val formatType: VideoFormatType = VideoFormatType.UNKNOWN,
    val companionUri: android.net.Uri? = null
)

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
            MediaStore.Video.Media.RELATIVE_PATH
        )
        
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Movies/BackgroundRecorder%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val duration = cursor.getLong(durationColumn)
                val size = cursor.getLong(sizeColumn)
                val date = cursor.getLong(dateColumn)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val format = DualFormatVideoHelper.detectVideoFormat(context, uri, name)

                rawVideos.add(
                    VideoMedia(
                        uri = uri,
                        name = name,
                        duration = duration,
                        size = size,
                        dateAdded = date,
                        formatType = format
                    )
                )
            }
        }

        // Group by base name to link companion video pairs (Vertical <-> Horizontal)
        val baseGroups = rawVideos.groupBy { DualFormatVideoHelper.getCleanBaseName(it.name) }
        
        val finalVideos = rawVideos.map { item ->
            val cleanBase = DualFormatVideoHelper.getCleanBaseName(item.name)
            val group = baseGroups[cleanBase] ?: emptyList()
            val companion = group.firstOrNull { other ->
                other.uri != item.uri && other.formatType != item.formatType
            }
            if (companion != null) {
                item.copy(companionUri = companion.uri)
            } else {
                item
            }
        }

        finalVideos
    }
}
