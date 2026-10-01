package com.rokid.cxrmsamples.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.rokid.cxrmsamples.managers.MediaCaptureManager
import com.rokid.cxrmsamples.managers.CapturedMedia
import kotlinx.coroutines.flow.StateFlow

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val mediaManager = MediaCaptureManager.getInstance(application)
    val mediaList: StateFlow<List<CapturedMedia>> = mediaManager.mediaList

    init {
        mediaManager.importVideosFromDisk()
    }
}
