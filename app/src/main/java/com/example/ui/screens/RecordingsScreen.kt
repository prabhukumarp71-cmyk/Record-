package com.example.ui.screens

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.media.MediaStoreHelper
import com.example.media.VideoMedia
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlayer: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<VideoMedia>>(emptyList()) }

    LaunchedEffect(Unit) {
        videos = MediaStoreHelper.getVideos(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No recordings found.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videos) { video ->
                    VideoItem(
                        video = video,
                        onClick = { onNavigateToPlayer(video.uri.toString()) },
                        onDelete = {
                            // Real deletion requires more complex logic with MediaStore and scoped storage (RecoverableSecurityException on Android 10).
                            // For simplicity, we just trigger the intent or remove from UI if successful.
                            try {
                                context.contentResolver.delete(video.uri, null, null)
                                coroutineScope.launch {
                                    videos = MediaStoreHelper.getVideos(context)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        onShare = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, video.uri)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VideoItem(
    video: VideoMedia,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(video.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(Date(video.dateAdded * 1000))
                val durationStr = "${video.duration / 1000}s"
                val sizeStr = Formatter.formatShortFileSize(context, video.size)
                Text("$dateStr • $durationStr • $sizeStr", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
