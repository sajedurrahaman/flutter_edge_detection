package com.jayfinava.flutteredgedetection.scan

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.widget.Toast
import com.jayfinava.flutteredgedetection.EdgeDetectionHandler
import com.jayfinava.flutteredgedetection.processor.Corners
import com.jayfinava.flutteredgedetection.processor.cropPicture
import com.jayfinava.flutteredgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.ROTATE_90_CLOCKWISE
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import android.util.Size as SizeB

class ScanPresenter constructor(
    private val context: Context,
    private val iView: IScanView.Proxy,
    private val initialBundle: Bundle
) : SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {

    private val TAG: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private var mCameraLensFacing: String? = null
    private var flashEnabled: Boolean = false

    private var mLastClickTime = 0L
    private var shutted: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var shutterFallbackRunnable: Runnable? = null
    private val captureInProgress = AtomicBoolean(false)

    // ── Auto / Manual capture mode ────────────────────────────────────────────
    private var isAutoMode: Boolean = initialBundle.getBoolean(EdgeDetectionHandler.AUTO_CAPTURE, true)

    fun setAutoMode(auto: Boolean) {
        isAutoMode = auto
        Log.i(TAG, "Capture mode: ${if (auto) "AUTO" else "MANUAL"}")
    }

    // Prevent auto-capture from firing repeatedly on the same document
    private var lastAutoCaptureTime = 0L
    private val AUTO_CAPTURE_COOLDOWN_MS = 3000L

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    private fun isOpenRecently(): Boolean {
        if (SystemClock.elapsedRealtime() - mLastClickTime < 3000) return true
        mLastClickTime = SystemClock.elapsedRealtime()
        return false
    }

    fun start() {
        mCamera?.startPreview() ?: Log.i(TAG, "mCamera startPreview")
    }

    fun stop() {
        mCamera?.stopPreview() ?: Log.i(TAG, "mCamera stopPreview")
    }

    val canShut: Boolean get() = shutted

    /**
     * Camera1 [Camera.autoFocus] often never invokes its callback when preview uses
     * [Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE] (common on Android 14–16).
     * That left [shutted] false forever, so the shutter appeared "dead". We capture
     * immediately in that case; for true AUTO/MACRO focus we still autoFocus with a timeout fallback.
     */
    fun shut() {
        if (isOpenRecently()) {
            Log.i(TAG, "NOT Taking click")
            return
        }
        val cam = mCamera
        if (cam == null) {
            Log.w(TAG, "shut: camera null")
            return
        }
        busy = true
        shutted = false

        shutterFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        shutterFallbackRunnable = null

        val params = cam.parameters
        val focusMode = params.focusMode
        val continuousFocus =
            focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE ||
                focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        val canTriggerAutoFocus =
            !continuousFocus &&
                params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true &&
                (focusMode == Camera.Parameters.FOCUS_MODE_AUTO ||
                    focusMode == Camera.Parameters.FOCUS_MODE_MACRO ||
                    focusMode == Camera.Parameters.FOCUS_MODE_EDOF)

        fun resetShutGate() {
            shutted = true
            busy = false
            captureInProgress.set(false)
        }

        fun takePictureAfterFocus() {
            if (!captureInProgress.compareAndSet(false, true)) return
            val c = mCamera
            if (c == null) {
                resetShutGate()
                return
            }
            try {
                c.enableShutterSound(false)
                c.takePicture(null, null, this)
            } catch (e: Exception) {
                Log.e(TAG, "takePicture failed: ${e.message}", e)
                captureInProgress.set(false)
                resetShutGate()
            }
        }

        if (canTriggerAutoFocus) {
            Log.i(TAG, "shutter: autoFocus then capture")
            val fallback = Runnable {
                Log.w(TAG, "autoFocus timeout — capturing anyway (Android 14+ safety)")
                takePictureAfterFocus()
            }
            shutterFallbackRunnable = fallback
            mainHandler.postDelayed(fallback, 2500L)
            try {
                cam.autoFocus { success, _ ->
                    Log.i(TAG, "focus result: $success")
                    shutterFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                    shutterFallbackRunnable = null
                    takePictureAfterFocus()
                }
            } catch (e: Exception) {
                Log.w(TAG, "autoFocus threw, capturing without it: ${e.message}")
                shutterFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                shutterFallbackRunnable = null
                takePictureAfterFocus()
            }
        } else {
            Log.i(TAG, "shutter: direct capture (continuous or fixed focus)")
            takePictureAfterFocus()
        }
    }

    fun toggleFlash() {
        try {
            val camera = mCamera ?: return
            val parameters = camera.parameters ?: return
            val supportedModes = parameters.supportedFlashModes ?: emptyList()
            val canTorch = supportedModes.contains(Camera.Parameters.FLASH_MODE_TORCH)
            val canOff = supportedModes.contains(Camera.Parameters.FLASH_MODE_OFF)
            if (!canTorch || !canOff) {
                Log.w(TAG, "toggleFlash: torch/off mode not supported on this device")
                return
            }

            flashEnabled = !flashEnabled
            parameters.flashMode = if (flashEnabled) {
                Camera.Parameters.FLASH_MODE_TORCH
            } else {
                Camera.Parameters.FLASH_MODE_OFF
            }

            camera.parameters = parameters
            mCamera?.startPreview()
        } catch (e: RuntimeException) {
            Log.e(TAG, "toggleFlash failed: ${e.message}", e)
            flashEnabled = false
        }
    }

    private fun updateCamera() {
        if (null == mCamera) return
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun getCameraCharacteristics(id: String): CameraCharacteristics =
        cameraManager.getCameraCharacteristics(id)

    private fun getBackFacingCameraId(): String? {
        for (camID in cameraManager.cameraIdList) {
            val lensFacing =
                getCameraCharacteristics(camID)[CameraCharacteristics.LENS_FACING]!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    private fun initCamera() {
        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(
                context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT
            ).show()
            return
        }

        val cameraCharacteristics =
            cameraManager.getCameraCharacteristics(getBackFacingCameraId()!!)
        val size = iView.getCurrentDisplay()?.let {
            getPreviewOutputSize(it, cameraCharacteristics, SurfaceHolder::class.java)
        }

        Log.i(TAG, "Selected preview size: ${size?.width} x ${size?.height}")

        val param = mCamera?.parameters
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)

        val display = iView.getCurrentDisplay()
        val point   = Point()
        display?.getRealSize(point)

        val displayWidth  = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio  = displayWidth.div(displayHeight.toFloat())
        val previewRatio  =
            size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio

        if (displayRatio > previewRatio) {
            val surfaceParams = iView.getSurfaceView().layoutParams
            surfaceParams.height =
                (displayHeight / displayRatio * previewRatio).toInt()
            iView.getSurfaceView().layoutParams = surfaceParams
        }

        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find {
            it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01
        }
        if (null == pictureSize) pictureSize = supportPicSize?.get(0)
        if (null == pictureSize) Log.e(TAG, "can not get picture size")
        else param?.setPictureSize(pictureSize.width, pictureSize.height)

        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS) &&
            mCamera!!.parameters.supportedFocusModes
                .contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
        ) {
            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.i(TAG, "enabling autofocus")
        } else {
            Log.i(TAG, "autofocus not available")
        }

        param?.flashMode = Camera.Parameters.FLASH_MODE_OFF
        mCamera?.parameters = param
        mCamera?.setDisplayOrientation(90)
        mCamera?.enableShutterSound(false)
    }

    private fun matrixResizer(sourceMatrix: Mat): Mat {
        val sourceSize: Size = sourceMatrix.size()
        var copied = Mat()
        if (sourceSize.height < sourceSize.width) {
            Core.rotate(sourceMatrix, copied, ROTATE_90_CLOCKWISE)
        } else {
            copied = sourceMatrix
        }
        val copiedSize: Size = copied.size()
        return if (copiedSize.width  > ScanConstants.MAX_SIZE.width ||
                   copiedSize.height > ScanConstants.MAX_SIZE.height) {
            val widthRatio  = ScanConstants.MAX_SIZE.width  / copiedSize.width
            val heightRatio = ScanConstants.MAX_SIZE.height / copiedSize.height
            val useRatio    = if (widthRatio > heightRatio) widthRatio else heightRatio
            val resizedImage = Mat()
            val newSize = Size(copiedSize.width * useRatio, copiedSize.height * useRatio)
            Imgproc.resize(copied, resizedImage, newSize)
            resizedImage
        } else {
            copied
        }
    }

    fun detectEdge(pic: Mat) {
        Log.i("height", pic.size().height.toString())
        Log.i("width",  pic.size().width.toString())
        val resizedMat = matrixResizer(pic)
        val corners = processPicture(resizedMat)
        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_RGB2BGRA)

        val pts = corners?.corners?.filterNotNull()
        val cropped = if (pts != null && pts.size == 4) {
            try { cropPicture(resizedMat, pts) } catch (e: Exception) {
                Log.e(TAG, "Auto-crop failed: ${e.message}")
                null
            }
        } else null

        val matToSave = cropped ?: resizedMat

        val outDir = File(context.cacheDir, "edge_scans").also { it.mkdirs() }
        val outFile = File(outDir, "scan_${System.currentTimeMillis()}.jpg")

        val bitmap = Bitmap.createBitmap(matToSave.width(), matToSave.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(matToSave, bitmap)
        val outStream = FileOutputStream(outFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
        outStream.flush()
        outStream.close()
        bitmap.recycle()

        if (cropped != null) cropped.release()
        resizedMat.release()

        iView.onImageCaptured(Uri.fromFile(outFile))
    }

    // Thumbnail generation moved to ScanActivity after crop/save.

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    override fun surfaceCreated(p0: SurfaceHolder)  { initCamera() }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        shutterFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        shutterFallbackRunnable = null
        captureInProgress.set(false)
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        }
    }

    // ── Camera.PictureCallback ────────────────────────────────────────────────

    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        captureInProgress.set(false)
        shutterFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        shutterFallbackRunnable = null
        Log.i(TAG, "on picture taken")
        Observable.just(p0)
            .subscribeOn(proxySchedule)
            .subscribe {
                val pictureSize = p1?.parameters?.pictureSize
                Log.i(TAG, "picture size: $pictureSize")
                val mat = Mat(
                    Size(
                        pictureSize?.width?.toDouble()  ?: 1920.0,
                        pictureSize?.height?.toDouble() ?: 1080.0
                    ),
                    CvType.CV_8U
                )
                mat.put(0, 0, p0)
                val pic = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
                Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                mat.release()
                detectEdge(pic)
                shutted = true
                busy    = false
            }
    }

    // ── Camera.PreviewCallback — AUTO-CAPTURE logic ───────────────────────────

    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) return
        busy = true

        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    val parameters = p1?.parameters
                    val width      = parameters?.previewSize?.width
                    val height     = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0,
                        parameters?.previewFormat ?: 0,
                        width  ?: 1080,
                        height ?: 1920,
                        null
                    )
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(
                        Rect(0, 0, width ?: 1080, height ?: 1920), 100, out
                    )
                    val bytes  = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val img    = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try { out.close() } catch (e: IOException) { e.printStackTrace() }

                    Observable.create<Corners> { emitter ->
                        val corner = processPicture(img)
                        busy = false
                        if (corner != null && corner.corners.size == 4) {
                            emitter.onNext(corner)
                        } else {
                            emitter.onError(Throwable("paper not detected"))
                        }
                    }
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { corners ->
                            iView.getPaperRect().onCornersDetected(corners)

                            // AUTO-CAPTURE: fire shutter when document is detected
                            if (isAutoMode && canShut) {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastAutoCaptureTime >= AUTO_CAPTURE_COOLDOWN_MS) {
                                    lastAutoCaptureTime = now
                                    Log.i(TAG, "Auto-capture triggered")
                                    shut()
                                }
                            }
                        },
                        {
                            iView.getPaperRect().onCornersNotDetected()
                        }
                    )
                }, { throwable ->
                    Log.e(TAG, throwable.message ?: "unknown preview error")
                })
        } catch (e: Exception) {
            Log.e(TAG, "onPreviewFrame exception: ${e.message}")
        }
    }

    // ── Camera size helpers ───────────────────────────────────────────────────

    class SmartSize(width: Int, height: Int) {
        var size  = SizeB(width, height)
        var long  = max(size.width, size.height)
        var short = min(size.width, size.height)
        override fun toString() = "SmartSize(${long}x${short})"
    }

    private val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

    private fun getDisplaySmartSize(display: Display): SmartSize {
        val outPoint = Point()
        display.getRealSize(outPoint)
        return SmartSize(outPoint.x, outPoint.y)
    }

    private fun <T> getPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
    ): SizeB {
        val screenSize = getDisplaySmartSize(display)
        val hdScreen   =
            screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
        val maxSize    = if (hdScreen) SIZE_1080P else screenSize

        val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
        if (format == null)
            assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
        else
            assert(config.isOutputSupportedFor(format))

        val allSizes =
            if (format == null) config.getOutputSizes(targetClass)
            else                config.getOutputSizes(format)

        val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }
            .reversed()

        return validSizes
            .first { it.long <= maxSize.long && it.short <= maxSize.short }
            .size
    }
}