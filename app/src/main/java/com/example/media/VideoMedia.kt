package com.example.media

import android.net.Uri

data class VideoMedia(
    val uri: Uri,
    val name: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val formatType: VideoFormatType = VideoFormatType.UNKNOWN,
    val companionUri: Uri? = null,
    val width: Int = 0,
    val height: Int = 0
)
