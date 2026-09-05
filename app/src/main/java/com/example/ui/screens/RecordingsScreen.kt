package com.example.ui.screens

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.media.DualFormatVideoHelper
import com.example.media.MediaStoreHelper
import com.example.media.VideoFormatType
import com.example.media.VideoMedia
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlayer: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<VideoMedia>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf<VideoFormatType?>(null) }
    var processingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    fun refreshVideos() {
        coroutineScope.launch {
            isLoading = true
            videos = MediaStoreHelper.getVideos(context)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshVideos()
    }

    val filteredVideos = remember(videos, selectedFilter) {
        if (selectedFilter == null) {
            videos
        } else {
            videos.filter { it.formatType == selectedFilter }
        }
    }

    val verticalCount = remember(videos) { videos.count { it.formatType == VideoFormatType.VERTICAL } }
    val horizontalCount = remember(videos) { videos.count { it.formatType == VideoFormatType.HORIZONTAL } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Recordings Library", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            "${videos.size} videos • $verticalCount vertical, $horizontalCount horizontal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshVideos() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Format Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All (${videos.size})") },
                    leadingIcon = {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )

                FilterChip(
                    selected = selectedFilter == VideoFormatType.VERTICAL,
                    onClick = { selectedFilter = if (selectedFilter == VideoFormatType.VERTICAL) null else VideoFormatType.VERTICAL },
                    label = { Text("📱 9:16 Vertical ($verticalCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = selectedFilter == VideoFormatType.HORIZONTAL,
                    onClick = { selectedFilter = if (selectedFilter == VideoFormatType.HORIZONTAL) null else VideoFormatType.HORIZONTAL },
                    label = { Text("🖥️ 16:9 Horizontal ($horizontalCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }

            if (isLoading && videos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredVideos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (selectedFilter != null) "No ${selectedFilter?.label} recordings found." else "No recordings found yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Recordings will appear here with both Vertical and Horizontal formats.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredVideos, key = { it.uri.toString() }) { video ->
                        val isConverting = processingUri == video.uri
                        VideoItem(
                            video = video,
                            isConverting = isConverting,
                            onClick = { onNavigateToPlayer(video.uri.toString(), video.companionUri?.toString()) },
                            onPlayCompanion = {
                                video.companionUri?.let { companion ->
                                    onNavigateToPlayer(companion.toString(), video.uri.toString())
                                }
                            },
                            onGenerateCompanion = {
                                coroutineScope.launch {
                                    processingUri = video.uri
                                    Toast.makeText(context, "Generating complementary format...", Toast.LENGTH_SHORT).show()
                                    val newUri = DualFormatVideoHelper.generateCompanionFormat(
                                        context = context,
                                        sourceUri = video.uri,
                                        originalDisplayName = video.name
                                    )
                                    processingUri = null
                                    if (newUri != null) {
                                        Toast.makeText(context, "✅ Created complementary format video!", Toast.LENGTH_SHORT).show()
                                        refreshVideos()
                                    } else {
                                        Toast.makeText(context, "Failed to generate format variant", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onDelete = {
                                try {
                                    context.contentResolver.delete(video.uri, null, null)
                                    refreshVideos()
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
}

@Composable
fun VideoItem(
    video: VideoMedia,
    isConverting: Boolean,
    onClick: () -> Unit,
    onPlayCompanion: () -> Unit,
    onGenerateCompanion: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val isVertical = video.formatType == VideoFormatType.VERTICAL
    val isHorizontal = video.formatType == VideoFormatType.HORIZONTAL

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("video_item_${video.name}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Format Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isVertical -> MaterialTheme.colorScheme.primaryContainer
                        isHorizontal -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when {
                                isVertical -> Icons.Default.PhoneAndroid
                                isHorizontal -> Icons.Default.Tv
                                else -> Icons.Default.Videocam
                            },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = when {
                                isVertical -> MaterialTheme.colorScheme.primary
                                isHorizontal -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when {
                                isVertical -> "Vertical (9:16)"
                                isHorizontal -> "Horizontal (16:9)"
                                else -> "Standard"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = when {
                                isVertical -> MaterialTheme.colorScheme.onPrimaryContainer
                                isHorizontal -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }
                }

                // Paired indicator or convert button
                if (video.companionUri != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable(onClick = onPlayCompanion)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isVertical) "Has 16:9 Pair" else "Has 9:16 Pair",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    if (isConverting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Generating...", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onGenerateCompanion,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isVertical) "+ Horizontal" else "+ Vertical",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val dateStr = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US).format(Date(video.dateAdded * 1000))
                    val durationStr = if (video.duration > 0) "${video.duration / 1000}s" else "<1s"
                    val sizeStr = Formatter.formatShortFileSize(context, video.size)
                    Text(
                        text = "$dateStr • $durationStr • $sizeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
