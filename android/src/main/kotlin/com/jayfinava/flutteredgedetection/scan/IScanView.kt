package com.jayfinava.flutteredgedetection.scan

import android.net.Uri
import android.view.Display
import android.view.SurfaceView
import com.jayfinava.flutteredgedetection.view.PaperRectangle

interface IScanView {
    interface Proxy {
        fun exit()
        fun getCurrentDisplay(): Display?
        fun getSurfaceView(): SurfaceView
        fun getPaperRect(): PaperRectangle

        /**
         * NEW — called by [ScanPresenter] each time a document image has been
         * captured and saved, so the Activity can append it to the thumbnail row.
         */
        fun onImageCaptured(uri: Uri)
    }
}