package com.rokid.cxrmsamples.ui.gallery

import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import android.widget.VideoView
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.cxrmsamples.R
import com.rokid.cxrmsamples.managers.CapturedMedia
import com.rokid.cxrmsamples.managers.MediaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val mediaList by viewModel.mediaList.collectAsState()
    var previewItem by remember { mutableStateOf<CapturedMedia?>(null) }

    if (mediaList.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.gallery_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(mediaList) { item ->
            MediaCard(item = item, onClick = { previewItem = item })
        }
    }

    previewItem?.let { item ->
        MediaPreviewDialog(
            item = item,
            onDismiss = { previewItem = null }
        )
    }
}

@Composable
private fun MediaCard(item: CapturedMedia, onClick: () -> Unit) {
    val timeText = remember(item.timestamp) {
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        format.format(Date(item.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column {
            if (item.type == MediaType.PHOTO && item.filePath != null) {
                val imageBitmap = remember(item.filePath) {
                    val file = File(item.filePath)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(item.filePath)?.asImageBitmap()
                    } else {
                        null
                    }
                }
                if (imageBitmap != null) {
                    ComposeImage(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.gallery_photo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                } else {
                    MediaPlaceholder(
                        label = stringResource(R.string.gallery_photo),
                        isVideo = false
                    )
                }
            } else {
                val thumbnail = remember(item.filePath) {
                    val path = item.filePath ?: return@remember null
                    val file = File(path)
                    if (!file.exists()) return@remember null
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ThumbnailUtils.createVideoThumbnail(file, Size(256, 256), null)
                        } else {
                            @Suppress("DEPRECATION")
                            ThumbnailUtils.createVideoThumbnail(path, 1)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (thumbnail != null) {
                    ComposeImage(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = stringResource(R.string.gallery_video),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                } else {
                    MediaPlaceholder(
                        label = stringResource(R.string.gallery_video),
                        isVideo = true
                    )
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = item.note ?: if (item.type == MediaType.PHOTO) {
                        stringResource(R.string.gallery_photo_label)
                    } else {
                        stringResource(R.string.gallery_video_label)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MediaPreviewDialog(item: CapturedMedia, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (item.type == MediaType.PHOTO && item.filePath != null) {
                val imageBitmap = remember(item.filePath) {
                    val file = File(item.filePath)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(item.filePath)?.asImageBitmap()
                    } else {
                        null
                    }
                }
                if (imageBitmap != null) {
                    ComposeImage(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.gallery_photo),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    MediaPreviewFallback()
                }
            } else if (item.filePath != null) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoPath(item.filePath)
                            setOnPreparedListener { it.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                )
            } else {
                MediaPreviewFallback()
            }
        }
    }
}

@Composable
private fun MediaPreviewFallback() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "文件不可用",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MediaPlaceholder(label: String, isVideo: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Icon(
                    imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
