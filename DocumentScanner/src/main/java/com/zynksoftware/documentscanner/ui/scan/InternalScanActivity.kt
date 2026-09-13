/**
Copyright 2020 ZynkSoftware SRL

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */


package com.zynksoftware.documentscanner.ui.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.zynksoftware.documentscanner.R
import com.zynksoftware.documentscanner.common.extensions.hide
import com.zynksoftware.documentscanner.common.extensions.show
import com.zynksoftware.documentscanner.manager.SessionManager
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel
import com.zynksoftware.documentscanner.model.ScanType
import com.zynksoftware.documentscanner.model.ScannerResults
import com.zynksoftware.documentscanner.ui.camerascreen.CameraScreenFragment
import com.zynksoftware.documentscanner.ui.components.ProgressView
import com.zynksoftware.documentscanner.ui.imagecrop.ImageCropFragment
import com.zynksoftware.documentscanner.ui.imageprocessing.ImageProcessingFragment
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.format
import id.zelory.compressor.constraint.quality
import id.zelory.compressor.constraint.size
import id.zelory.compressor.extension
import id.zelory.compressor.saveBitmap
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File


abstract class InternalScanActivity : AppCompatActivity() {

    abstract fun onError(error: DocumentScannerErrorModel)
    abstract fun onSuccess(scannerResults: ScannerResults)
    abstract fun onClose()

    companion object {
        private val TAG = InternalScanActivity::class.simpleName
        internal const val CAMERA_SCREEN_FRAGMENT_TAG = "CameraScreenFragmentTag"
        internal const val IMAGE_CROP_FRAGMENT_TAG = "ImageCropFragmentTag"
        internal const val IMAGE_PROCESSING_FRAGMENT_TAG = "ImageProcessingFragmentTag"
        internal const val ORIGINAL_IMAGE_NAME = "original"
        internal const val CROPPED_IMAGE_NAME = "cropped"
        internal const val TRANSFORMED_IMAGE_NAME = "transformed"
        internal const val NOT_INITIALIZED = -1L
        const val EXTRA_SCAN_TYPE = "EXTRA_SCAN_TYPE"
    }

    internal lateinit var originalImageFile: File
    internal var croppedImage: Bitmap? = null
    internal var transformedImage: Bitmap? = null
    private var imageQuality: Int = 100
    private var imageSize: Long = NOT_INITIALIZED
    internal var galleryButtonEnabled: Boolean = false
    private lateinit var imageType: Bitmap.CompressFormat
    internal var shouldCallOnClose = true

    var scanType: ScanType = ScanType.DOCUMENT
    var cardStep: Int = 1
    var firstCardBitmap: Bitmap? = null
    var secondCardBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        val sessionManager = SessionManager(this)
        imageType = sessionManager.getImageType()
        imageSize = sessionManager.getImageSize()
        imageQuality = sessionManager.getImageQuality()
        galleryButtonEnabled = sessionManager.isGalleryButtonEnabled()

        intent?.getStringExtra(EXTRA_SCAN_TYPE)?.let {
            try {
                scanType = ScanType.valueOf(it)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid ScanType", e)
            }
        }

        reInitOriginalImageFile()
    }

    internal fun reInitOriginalImageFile() {
        originalImageFile = File(filesDir, "${ORIGINAL_IMAGE_NAME}.${imageType.extension()}")
        originalImageFile.delete()
    }

    private fun showCameraScreen() {
        val cameraScreenFragment = CameraScreenFragment.newInstance()
        addFragmentToBackStack(cameraScreenFragment, CAMERA_SCREEN_FRAGMENT_TAG)
    }

    internal fun showImageCropFragment() {
        val imageCropFragment = ImageCropFragment.newInstance()
        addFragmentToBackStack(imageCropFragment, IMAGE_CROP_FRAGMENT_TAG)
    }

    internal fun showImageProcessingFragment() {
        val imageProcessingFragment = ImageProcessingFragment.newInstance()
        addFragmentToBackStack(imageProcessingFragment, IMAGE_PROCESSING_FRAGMENT_TAG)
    }

    internal fun closeCurrentFragment() {
        supportFragmentManager.popBackStackImmediate()
    }

    private fun addFragmentToBackStack(fragment: Fragment, fragmentTag: String) {
        val fragmentTransaction: FragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.zdcContent, fragment, fragmentTag)
        if (supportFragmentManager.findFragmentByTag(fragmentTag) == null) {
            fragmentTransaction.addToBackStack(fragmentTag)
        }
        fragmentTransaction.commit()
    }

    internal fun handleScanStep() {
        if (scanType == ScanType.ID_CARD) {
            val currentBitmap = transformedImage ?: croppedImage
            if (currentBitmap != null) {
                if (cardStep == 1) {
                    firstCardBitmap = currentBitmap.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                    cardStep = 2
                    croppedImage = null
                    transformedImage = null
                    reInitOriginalImageFile()

                    Toast.makeText(this, "تم مسح الوجه الأول بنجاح! يرجى مسح الوجه الثاني للبطاقة", Toast.LENGTH_LONG).show()

                    val popped = supportFragmentManager.popBackStackImmediate(
                        CAMERA_SCREEN_FRAGMENT_TAG,
                        0
                    )
                    if (!popped) {
                        showCameraScreen()
                    }
                    findViewById<FrameLayout>(R.id.zdcContent).show()
                    return
                } else if (cardStep == 2) {
                    secondCardBitmap = currentBitmap.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                    if (firstCardBitmap != null && secondCardBitmap != null) {
                        val merged = mergeIdCardBitmaps(firstCardBitmap!!, secondCardBitmap!!)
                        croppedImage = merged
                        transformedImage = merged
                    }
                }
            }
        }
        finalScannerResult()
    }

    private fun mergeIdCardBitmaps(front: Bitmap, back: Bitmap): Bitmap {
        val cardWidth = maxOf(front.width, back.width)
        val cardHeight = maxOf(front.height, back.height)

        val padding = (cardWidth * 0.06f).toInt()
        val headerHeight = (cardWidth * 0.08f).toInt()

        val canvasWidth = cardWidth + (padding * 2)
        val canvasHeight = (cardHeight * 2) + (padding * 3) + (headerHeight * 2)

        val merged = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 40, 60)
            textSize = cardWidth * 0.045f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        var currentY = padding.toFloat()

        // Front Side
        canvas.drawText("الوجه الأمامي (Front Side)", canvasWidth / 2f, currentY + (headerHeight * 0.6f), textPaint)
        currentY += headerHeight

        val frontLeft = (canvasWidth - front.width) / 2f
        val frontRect = RectF(frontLeft, currentY, frontLeft + front.width, currentY + front.height)
        canvas.drawBitmap(front, frontLeft, currentY, paint)
        canvas.drawRect(frontRect, borderPaint)

        currentY += front.height + padding

        // Back Side
        canvas.drawText("الوجه الخلفي (Back Side)", canvasWidth / 2f, currentY + (headerHeight * 0.6f), textPaint)
        currentY += headerHeight

        val backLeft = (canvasWidth - back.width) / 2f
        val backRect = RectF(backLeft, currentY, backLeft + back.width, currentY + back.height)
        canvas.drawBitmap(back, backLeft, currentY, paint)
        canvas.drawRect(backRect, borderPaint)

        return merged
    }

    internal fun finalScannerResult() {
        findViewById<FrameLayout>(R.id.zdcContent).hide()
        compressFiles()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun compressFiles() {
        Log.d(TAG, "ZDCcompress starts ${System.currentTimeMillis()}")
        findViewById<ProgressView>(R.id.zdcProgressView).show()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (scanType == ScanType.ID_CARD && (croppedImage != null || transformedImage != null)) {
                    val mergedBitmap = transformedImage ?: croppedImage
                    mergedBitmap?.let {
                        saveBitmap(it, originalImageFile, imageType, imageQuality)
                    }
                }

                var croppedImageFile: File? = null
                croppedImage?.let {
                    croppedImageFile = File(filesDir, "${CROPPED_IMAGE_NAME}.${imageType.extension()}")
                    saveBitmap(it, croppedImageFile!!, imageType, imageQuality)
                }

                var transformedImageFile: File? = null
                transformedImage?.let {
                    transformedImageFile =
                        File(filesDir, "${TRANSFORMED_IMAGE_NAME}.${imageType.extension()}")
                    saveBitmap(it, transformedImageFile!!, imageType, imageQuality)
                }

                if (originalImageFile.exists() && originalImageFile.length() > 0) {
                    try {
                        originalImageFile = Compressor.compress(this@InternalScanActivity, originalImageFile) {
                            quality(imageQuality)
                            if (imageSize != NOT_INITIALIZED) size(imageSize)
                            format(imageType)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error compressing originalImageFile", e)
                    }
                }

                croppedImageFile = croppedImageFile?.let {
                    if (it.exists() && it.length() > 0) {
                        try {
                            Compressor.compress(this@InternalScanActivity, it) {
                                quality(imageQuality)
                                if (imageSize != NOT_INITIALIZED) size(imageSize)
                                format(imageType)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error compressing croppedImageFile", e)
                            it
                        }
                    } else null
                }

                transformedImageFile = transformedImageFile?.let {
                    if (it.exists() && it.length() > 0) {
                        try {
                            Compressor.compress(this@InternalScanActivity, it) {
                                quality(imageQuality)
                                if (imageSize != NOT_INITIALIZED) size(imageSize)
                                format(imageType)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error compressing transformedImageFile", e)
                            it
                        }
                    } else null
                }

                val scannerResults =
                    ScannerResults(originalImageFile, croppedImageFile, transformedImageFile)
                runOnUiThread {
                    findViewById<ProgressView>(R.id.zdcProgressView).hide()
                    shouldCallOnClose = false
                    supportFragmentManager.popBackStackImmediate(
                        null,
                        FragmentManager.POP_BACK_STACK_INCLUSIVE
                    )
                    shouldCallOnClose = true
                    onSuccess(scannerResults)
                    Log.d(TAG, "ZDCcompress ends ${System.currentTimeMillis()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compressing files", e)
                runOnUiThread {
                    findViewById<ProgressView>(R.id.zdcProgressView).hide()
                    onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.INVALID_IMAGE, e))
                }
            }
        }
    }

    internal fun addFragmentContentLayoutInternal() {
        val frameLayout = FrameLayout(this)
        frameLayout.id = R.id.zdcContent
        addContentView(
            frameLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val progressView = ProgressView(this)
        progressView.id = R.id.zdcProgressView
        addContentView(
            progressView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        progressView.hide()

        showCameraScreen()
    }

    private fun setupEdgeToEdge() {
        enableEdgeToEdge()
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}
