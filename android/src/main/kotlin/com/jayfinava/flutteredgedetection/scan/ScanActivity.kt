package com.jayfinava.flutteredgedetection.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.annotation.RequiresApi
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.jayfinava.flutteredgedetection.ERROR_CODE
import com.jayfinava.flutteredgedetection.EdgeDetectionHandler
import com.jayfinava.flutteredgedetection.R
import com.jayfinava.flutteredgedetection.REQUEST_CODE
import com.jayfinava.flutteredgedetection.base.BaseActivity
import com.jayfinava.flutteredgedetection.view.PaperRectangle
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.*

class ScanActivity : BaseActivity(), IScanView.Proxy {

    private lateinit var mPresenter: ScanPresenter

    /** Saved (cropped) images for the session. */
    private val capturedImageUris = mutableListOf<Uri>()

    /** Pending gallery URIs to process one-by-one (multi-select). */
    private val pendingGalleryUris = ArrayDeque<Uri>()

    // Track auto/manual state — default is MANUAL (false)
    private var isAutoEnabled = false

    // Track flash state — default is OFF
    private var isFlashOn = false

    // Native mode strip selection (documents stays here; others return to Flutter)
    private var selectedMode: String = "documents"

    private var canUseGallery: Boolean = true

    private val modeOrder =
        listOf("documents", "books", "qrcode", "barcode", "idcard", "passport", "visitingcard", "area")
    private val snapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isProgrammaticScroll = false
    private var scrollStartIndex = 0
    private val snapRunnable = Runnable { snapToNearestMode() }

    override fun provideContentViewId(): Int = R.layout.activity_scan

    override fun initPresenter() {
        val initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle
        mPresenter = ScanPresenter(this, this, initialBundle)
    }

    override fun prepare() {
        supportActionBar?.hide()

        // Push top bar below notch/status bar (window insets).
        val topBar = findViewById<View>(R.id.top_bar)
        val initialTopPadding = topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, initialTopPadding + 38, v.paddingRight, v.paddingBottom - 4)
            insets
        }

        if (!OpenCVLoader.initDebug()) {
            Log.i(TAG, "loading opencv error, exit")
            finish()
        } else {
            Log.i("OpenCV", "OpenCV loaded Successfully!")
        }

        val shut = findViewById<View>(R.id.shut)
        val shutAfter = findViewById<View>(R.id.shut_after)
        val shutterClick = View.OnClickListener {
            if (mPresenter.canShut) mPresenter.shut()
        }
        shut.setOnClickListener(shutterClick)
        shutAfter.setOnClickListener(shutterClick)

        // ── Flash button ──────────────────────────────────────────────────────
        val flashView = findViewById<ImageView>(R.id.flash)
        val hasFlash = hasTorchSupport()

        flashView.visibility = if (hasFlash) View.VISIBLE else View.GONE

        // Set initial icon to flash-off state
        isFlashOn = false
        applyFlashState(flashView)

        flashView.setOnClickListener {
            isFlashOn = !isFlashOn
            applyFlashState(flashView)
            mPresenter.toggleFlash()
        }
        // ─────────────────────────────────────────────────────────────────────

        // Close (X) – top left
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }

        // Done – top right (only visible after at least 1 image)
        findViewById<View>(R.id.btn_done).setOnClickListener { finishWithSessionResult(openCrop = false) }
        // Thumbnail tap – open editor flow in Flutter with current session images.
        findViewById<View>(R.id.thumb_container).setOnClickListener {
            if (capturedImageUris.isNotEmpty()) finishWithSessionResult(openCrop = true)
        }

        // Import Image – bottom initial
        findViewById<View>(R.id.btn_import_image).setOnClickListener { pickImagesFromGallery() }

        // Import File (PDF) – bottom initial
        findViewById<View>(R.id.btn_import_file).setOnClickListener { pickPdfFile() }

        // Undo – bottom after
        findViewById<View>(R.id.btn_undo).setOnClickListener { undoLast() }

        val initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle

        // ── Auto / Manual toggle ──────────────────────────────────────────────
        val autoToggleContainer = findViewById<LinearLayout>(R.id.auto_manual_switch)
        val autoIcon            = findViewById<ImageView>(R.id.auto_capture_switch)
        val autoLabel           = findViewById<TextView>(R.id.auto_manual_label)

        isAutoEnabled = false
        applyAutoState(autoLabel, autoIcon)
        mPresenter.setAutoMode(isAutoEnabled)

        autoToggleContainer.setOnClickListener {
            isAutoEnabled = !isAutoEnabled
            applyAutoState(autoLabel, autoIcon)
            mPresenter.setAutoMode(isAutoEnabled)
        }
        // ─────────────────────────────────────────────────────────────────────

        // Hide import buttons if gallery not allowed
        canUseGallery = initialBundle.getBoolean(EdgeDetectionHandler.CAN_USE_GALLERY, true)
        findViewById<View>(R.id.btn_import_image).visibility = if (canUseGallery) View.VISIBLE else View.GONE
        // File import will be additionally controlled by selected mode.
        findViewById<View>(R.id.btn_import_file).visibility = if (canUseGallery) View.INVISIBLE else View.GONE

        if (!initialBundle.containsKey(EdgeDetectionHandler.FROM_GALLERY)) {
            this.title = initialBundle.getString(EdgeDetectionHandler.SCAN_TITLE, "") as String
        }

        if (initialBundle.containsKey(EdgeDetectionHandler.FROM_GALLERY) &&
            initialBundle.getBoolean(EdgeDetectionHandler.FROM_GALLERY, false)
        ) {
            val selectedImageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                initialBundle.getParcelable("SELECTED_IMAGE_URI", android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                initialBundle.getParcelable("SELECTED_IMAGE_URI")
            }
            if (selectedImageUri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    onImageSelected(selectedImageUri)
                }
            } else {
                pickImagesFromGallery()
            }
        }

        // Mode strip (inside native camera screen)
        setupModeStrip()

        val initialMode = initialBundle.getString(EdgeDetectionHandler.INITIAL_MODE, "documents") ?: "documents"

        val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)
        val innerLayout = findViewById<LinearLayout>(R.id.mode_strip_inner)
        modeStrip.post {
            val halfWidth = modeStrip.width / 2
            innerLayout.setPadding(halfWidth, innerLayout.paddingTop, halfWidth, innerLayout.paddingBottom)

            innerLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    innerLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    isProgrammaticScroll = true
                    setActiveMode(initialMode)
                    val modeIndex = modeOrder.indexOf(initialMode).coerceAtLeast(0)
                    scrollStartIndex = modeIndex
                    if (modeIndex < innerLayout.childCount) {
                        val child = innerLayout.getChildAt(modeIndex)
                        val scrollTo = child.left + child.width / 2 - modeStrip.width / 2
                        modeStrip.scrollTo(scrollTo.coerceAtLeast(0), 0)
                    }
                    modeStrip.postDelayed({ isProgrammaticScroll = false }, 300)
                    setupModeStripScrollListener()
                }
            })
        }

        updateUiState()
    }

    /**
     * Returns true when the device can drive a rear-camera torch.
     * Uses Camera2 characteristics first (reliable on Android 14+) and
     * falls back to package feature detection for older/limited devices.
     */
    private fun hasTorchSupport(): Boolean {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.any { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hasFlashUnit = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val isBackCamera =
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlashUnit && isBackCamera
            }
        } catch (_: Exception) {
            // Fallback if camera characteristics are unavailable.
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        }
    }

    private fun setupModeStrip() {
        val items = listOf(
            "documents" to R.id.mode_documents,
            "books" to R.id.mode_books,
            "qrcode" to R.id.mode_qrcode,
            "barcode" to R.id.mode_barcode,
            "idcard" to R.id.mode_idcard,
            "passport" to R.id.mode_passport,
            "visitingcard" to R.id.mode_visitingcard,
            "area" to R.id.mode_area,
        )

        for ((mode, viewId) in items) {
            findViewById<View>(viewId).setOnClickListener {
                isProgrammaticScroll = true
                setActiveMode(mode)
                scrollModeToCenter(mode)
                val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)
                if (mode != "documents" && mode != "books") {
                    modeStrip.postDelayed({ finishWithModeResult(mode) }, 300)
                }
                modeStrip.postDelayed({ isProgrammaticScroll = false }, 400)
            }
        }
    }

    private fun setActiveMode(mode: String) {
        selectedMode = mode
        val textIds = mapOf(
            "documents" to R.id.mode_documents,
            "books" to R.id.mode_books,
            "qrcode" to R.id.mode_qrcode,
            "barcode" to R.id.mode_barcode,
            "idcard" to R.id.mode_idcard,
            "passport" to R.id.mode_passport,
            "visitingcard" to R.id.mode_visitingcard,
            "area" to R.id.mode_area,
        )
        for ((m, textId) in textIds) {
            val tv = findViewById<TextView>(textId)
            tv.setTextColor(if (m == mode) android.graphics.Color.RED else android.graphics.Color.WHITE)
        }

        // Import file should only show for Documents (not Book mode).
        val importFile = findViewById<View>(R.id.btn_import_file)
        importFile.visibility = when {
            canUseGallery && mode == "documents" -> View.VISIBLE
            canUseGallery -> View.INVISIBLE // keep space so shutter stays centered
            else -> View.GONE
        }
    }

    private fun scrollModeToCenter(mode: String) {
        val index = modeOrder.indexOf(mode)
        if (index < 0) return
        val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)
        val innerLayout = findViewById<LinearLayout>(R.id.mode_strip_inner)
        if (index >= innerLayout.childCount) return
        val child = innerLayout.getChildAt(index)
        val scrollTo = child.left + child.width / 2 - modeStrip.width / 2
        modeStrip.smoothScrollTo(scrollTo.coerceAtLeast(0), 0)
    }

    private fun setupModeStripScrollListener() {
        val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)

        modeStrip.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    scrollStartIndex = getCenteredModeIndex()
                    snapHandler.removeCallbacks(snapRunnable)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    snapHandler.removeCallbacks(snapRunnable)
                    v.post { snapToNearestMode() }
                }
            }
            false
        }

        modeStrip.viewTreeObserver.addOnScrollChangedListener {
            if (!isProgrammaticScroll) {
                updateHighlightedMode()
            }
        }
    }

    private fun getCenteredModeIndex(): Int {
        val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)
        val innerLayout = findViewById<LinearLayout>(R.id.mode_strip_inner)
        val center = modeStrip.scrollX + modeStrip.width / 2

        var nearestIndex = 0
        var minDist = Int.MAX_VALUE
        for (i in 0 until innerLayout.childCount) {
            val child = innerLayout.getChildAt(i)
            val childCenter = child.left + child.width / 2
            val dist = kotlin.math.abs(center - childCenter)
            if (dist < minDist) {
                minDist = dist
                nearestIndex = i
            }
        }
        return nearestIndex.coerceIn(0, modeOrder.size - 1)
    }

    private fun updateHighlightedMode() {
        val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)
        val innerLayout = findViewById<LinearLayout>(R.id.mode_strip_inner)
        val center = modeStrip.scrollX + modeStrip.width / 2

        for (i in 0 until innerLayout.childCount) {
            val child = innerLayout.getChildAt(i)
            val childCenter = child.left + child.width / 2
            val isCenter = kotlin.math.abs(center - childCenter) < child.width / 2
            if (child is TextView) {
                child.setTextColor(if (isCenter) android.graphics.Color.RED else android.graphics.Color.WHITE)
            }
        }
    }

    private fun snapToNearestMode() {
        val modeStrip = findViewById<HorizontalScrollView>(R.id.mode_strip)
        val innerLayout = findViewById<LinearLayout>(R.id.mode_strip_inner)
        val center = modeStrip.scrollX + modeStrip.width / 2

        var rawNearestIndex = scrollStartIndex
        var minDist = Int.MAX_VALUE
        for (i in 0 until innerLayout.childCount) {
            val child = innerLayout.getChildAt(i)
            val childCenter = child.left + child.width / 2
            val dist = kotlin.math.abs(center - childCenter)
            if (dist < minDist) {
                minDist = dist
                rawNearestIndex = i
            }
        }

        val targetIndex = when {
            rawNearestIndex > scrollStartIndex -> (scrollStartIndex + 1).coerceAtMost(modeOrder.size - 1)
            rawNearestIndex < scrollStartIndex -> (scrollStartIndex - 1).coerceAtLeast(0)
            else -> scrollStartIndex
        }.coerceIn(0, innerLayout.childCount - 1)

        val targetChild = innerLayout.getChildAt(targetIndex)
        if (targetChild != null && targetIndex < modeOrder.size) {
            val mode = modeOrder[targetIndex]
            isProgrammaticScroll = true
            val scrollTo = targetChild.left + targetChild.width / 2 - modeStrip.width / 2
            modeStrip.smoothScrollTo(scrollTo.coerceAtLeast(0), 0)
            scrollStartIndex = targetIndex

            if (mode != selectedMode) {
                setActiveMode(mode)
                if (mode != "documents" && mode != "books") {
                    modeStrip.postDelayed({ finishWithModeResult(mode) }, 350)
                }
            }
            modeStrip.postDelayed({ isProgrammaticScroll = false }, 400)
        }
    }

    private fun finishWithModeResult(mode: String) {
        val intent = Intent().apply { putExtra("mode", mode) }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    /**
     * Swaps the flash ImageView icon based on [isFlashOn].
     *
     * Flash ON  → ic_baseline_flash_on_24  (full white)
     * Flash OFF → ic_baseline_flash_off_24 (dimmed white)
     *
     * Make sure you have both drawables in res/drawable:
     *   - ic_baseline_flash_on_24.xml
     *   - ic_baseline_flash_off_24.xml
     */
    private fun applyFlashState(flashView: ImageView) {
        if (isFlashOn) {
            flashView.setImageResource(R.drawable.flash_on)
            flashView.alpha = 1.0f          // fully lit — flash is active
        } else {
            flashView.setImageResource(R.drawable.flash_off)
            flashView.alpha = 0.5f          // dimmed — flash is off
        }
    }

    /**
     * Applies the current [isAutoEnabled] state to the label and icon.
     *
     * ON  (Auto)   → full opacity, label = "Auto"
     * OFF (Manual) → dimmed opacity, label = "Manual"
     */
    private fun applyAutoState(label: TextView, icon: ImageView) {
        if (isAutoEnabled) {
            label.text  = "Auto"
            icon.alpha  = 1.0f
            label.alpha = 1.0f
        } else {
            label.text  = "Manual"
            icon.alpha  = 0.4f
            label.alpha = 0.4f
        }
    }

    private fun pickImagesFromGallery() {
        mPresenter.stop()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        ActivityCompat.startActivityForResult(this, intent, 1, null)
    }

    private fun pickPdfFile() {
        mPresenter.stop()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        ActivityCompat.startActivityForResult(this, intent, 2, null)
    }

    override fun onStart() {
        super.onStart()
        mPresenter.start()
    }

    override fun onStop() {
        super.onStop()
        mPresenter.stop()
    }

    override fun exit() {
        finish()
    }

    override fun onImageCaptured(uri: Uri) {
        runOnUiThread {
            capturedImageUris.add(uri)
            updateUiState()
            if (pendingGalleryUris.isNotEmpty()) processNextPendingGallery() else mPresenter.start()
        }
    }

    private fun updateBadgeAndThumbnail() {
        val badge = findViewById<TextView>(R.id.import_badge)
        val thumb = findViewById<ImageView>(R.id.thumb_preview)
        val count = capturedImageUris.size
        if (count == 0) {
            badge.visibility = View.GONE
            thumb.setImageDrawable(null)
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = if (count > 99) "99" else String.format("%02d", count)

        val last = capturedImageUris.last()
        val bmp = decodeThumbnailForUri(last)
        thumb.setImageBitmap(bmp)
    }

    /** Downsampled decode so undo / refresh does not load full-resolution bitmaps (avoids OOM). */
    private fun decodeThumbnailForUri(uri: Uri, maxSidePx: Int = 256): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            if (uri.scheme == "content") {
                contentResolver.openInputStream(uri)?.use { input ->
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeStream(input, null, options)
                } ?: return null
                options.inSampleSize =
                    thumbnailInSampleSize(options.outWidth, options.outHeight, maxSidePx)
                options.inJustDecodeBounds = false
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            } else {
                val path = uri.path
                if (path.isNullOrEmpty()) return null
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, options)
                options.inSampleSize =
                    thumbnailInSampleSize(options.outWidth, options.outHeight, maxSidePx)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail decode failed: ${e.message}")
            null
        }
    }

    private fun thumbnailInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var inSampleSize = 1
        while (width / inSampleSize > maxSidePx && height / inSampleSize > maxSidePx) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun updateUiState() {
        val hasAny = capturedImageUris.isNotEmpty()
        findViewById<View>(R.id.btn_done).visibility           = if (hasAny) View.VISIBLE else View.GONE
        findViewById<View>(R.id.bottom_bar_initial).visibility = if (hasAny) View.GONE    else View.VISIBLE
        findViewById<View>(R.id.bottom_bar_after).visibility   = if (hasAny) View.VISIBLE else View.GONE
        updateBadgeAndThumbnail()
    }

    private fun undoLast() {
        if (capturedImageUris.isEmpty()) return
        val last = capturedImageUris.removeAt(capturedImageUris.lastIndex)
        try {
            when (last.scheme) {
                "content" -> contentResolver.delete(last, null, null)
                else -> last.path?.let { p ->
                    val f = File(p)
                    if (f.exists()) f.delete()
                }
            }
        } catch (_: Exception) {}
        updateUiState()
    }

    private fun enqueueAndProcessGallerySelection(data: Intent) {
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) pendingGalleryUris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { pendingGalleryUris.add(it) }
        }
        processNextPendingGallery()
    }

    /**
     * For document/book modes: import picked gallery images directly
     * and return session result to Flutter (skip native crop flow).
     */
    private fun importGallerySelectionDirectly(data: Intent): Boolean {
        val selectedUris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { selectedUris.add(it) }
            }
        } else {
            data.data?.let { selectedUris.add(it) }
        }
        if (selectedUris.isEmpty()) return false

        val outDir = File(cacheDir, "edge_scans").also { it.mkdirs() }
        var importedAny = false
        selectedUris.forEachIndexed { index, uri ->
            try {
                val outFile = File(
                    outDir,
                    "import_img_${System.currentTimeMillis()}_${index}.jpg"
                )
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                if (outFile.exists()) {
                    capturedImageUris.add(Uri.fromFile(outFile))
                    importedAny = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct gallery import failed: ${e.message}")
            }
        }
        return importedAny
    }

    private fun processNextPendingGallery() {
        val next = pendingGalleryUris.removeFirstOrNull() ?: run {
            mPresenter.start()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            onImageSelected(next)
        } else {
            mPresenter.start()
        }
    }

    private fun finishWithSessionResult(openCrop: Boolean = false) {
        val intent = Intent().apply {
            putStringArrayListExtra(
                "uris",
                ArrayList(capturedImageUris.mapNotNull { it.path })
            )
            putExtra("open_crop", openCrop)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun getCurrentDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display
        } else {
            this.windowManager.defaultDisplay
        }
    }

    override fun getSurfaceView()  = findViewById<SurfaceView>(R.id.surface)
    override fun getPaperRect()    = findViewById<PaperRectangle>(R.id.paper_rect)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val savedPath = data?.getStringExtra("saved_uri")
                if (savedPath != null) {
                    capturedImageUris.add(Uri.fromFile(File(savedPath)))
                    updateUiState()
                }
                if (pendingGalleryUris.isNotEmpty()) processNextPendingGallery() else mPresenter.start()
            } else {
                if (intent.hasExtra(EdgeDetectionHandler.FROM_GALLERY) &&
                    intent.getBooleanExtra(EdgeDetectionHandler.FROM_GALLERY, false)
                ) finish()
            }
        }

        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    if (selectedMode == "documents" || selectedMode == "books") {
                        val imported = importGallerySelectionDirectly(data)
                        if (imported) {
                            finishWithSessionResult(openCrop = false)
                        } else {
                            mPresenter.start()
                        }
                    } else {
                        enqueueAndProcessGallerySelection(data)
                    }
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                mPresenter.start()
            } else {
                if (intent.hasExtra(EdgeDetectionHandler.FROM_GALLERY) &&
                    intent.getBooleanExtra(EdgeDetectionHandler.FROM_GALLERY, false)
                ) finish()
            }
        }

        if (requestCode == 2) {
            if (resultCode == Activity.RESULT_OK) {
                val pdfUri = data?.data
                if (pdfUri != null) {
                    try {
                        val outDir  = File(cacheDir, "edge_scans").also { it.mkdirs() }
                        val outFile = File(outDir, "import_${System.currentTimeMillis()}.pdf")
                        contentResolver.openInputStream(pdfUri)?.use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        }
                        val intent = Intent().apply { putExtra("pdf_path", outFile.absolutePath) }
                        setResult(Activity.RESULT_OK, intent)
                        finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "PDF import failed: ${e.message}")
                        mPresenter.start()
                    }
                } else {
                    mPresenter.start()
                }
            } else {
                mPresenter.start()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressed(); true }
        else -> super.onOptionsItemSelected(item)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun onImageSelected(imageUri: Uri) {
        try {
            val iStream: InputStream = contentResolver.openInputStream(imageUri)!!
            val exif = ExifInterface(iStream)
            var rotation = -1
            val orientation: Int = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> rotation = Core.ROTATE_90_CLOCKWISE
                ExifInterface.ORIENTATION_ROTATE_180 -> rotation = Core.ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_270 -> rotation = Core.ROTATE_90_COUNTERCLOCKWISE
            }
            val mimeType = contentResolver.getType(imageUri)
            var imageWidth: Double
            var imageHeight: Double

            if (mimeType?.startsWith("image/png") == true) {
                val source   = ImageDecoder.createSource(contentResolver, imageUri)
                val drawable = ImageDecoder.decodeDrawable(source)
                imageWidth  = drawable.intrinsicWidth.toDouble()
                imageHeight = drawable.intrinsicHeight.toDouble()
                if (rotation == Core.ROTATE_90_CLOCKWISE || rotation == Core.ROTATE_90_COUNTERCLOCKWISE) {
                    imageWidth  = drawable.intrinsicHeight.toDouble()
                    imageHeight = drawable.intrinsicWidth.toDouble()
                }
            } else {
                imageWidth  = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).toDouble()
                imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).toDouble()
                if (rotation == Core.ROTATE_90_CLOCKWISE || rotation == Core.ROTATE_90_COUNTERCLOCKWISE) {
                    imageWidth  = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).toDouble()
                    imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).toDouble()
                }
            }

            val inputData: ByteArray? = getBytes(contentResolver.openInputStream(imageUri)!!)
            val mat = Mat(Size(imageWidth, imageHeight), CvType.CV_8U)
            mat.put(0, 0, inputData)
            val pic = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
            if (rotation > -1) Core.rotate(pic, pic, rotation)
            mat.release()

            mPresenter.detectEdge(pic)
        } catch (error: Exception) {
            val intent = Intent()
            intent.putExtra("RESULT", error.toString())
            setResult(ERROR_CODE, intent)
            finish()
        }
    }

    @Throws(IOException::class)
    fun getBytes(inputStream: InputStream): ByteArray? {
        val byteBuffer = ByteArrayOutputStream()
        val bufferSize = 1024
        val buffer     = ByteArray(bufferSize)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            byteBuffer.write(buffer, 0, len)
        }
        return byteBuffer.toByteArray()
    }
}