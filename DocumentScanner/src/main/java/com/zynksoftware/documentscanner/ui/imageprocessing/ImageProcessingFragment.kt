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


package com.zynksoftware.documentscanner.ui.imageprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.zynksoftware.documentscanner.common.extensions.rotateBitmap
import com.zynksoftware.documentscanner.databinding.FragmentImageProcessingBinding
import com.zynksoftware.documentscanner.ui.base.BaseFragment
import com.zynksoftware.documentscanner.ui.scan.InternalScanActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

internal class ImageProcessingFragment : BaseFragment() {
    private var _binding: FragmentImageProcessingBinding? = null
    private val binding get() = _binding!!

    companion object {
        private val TAG = ImageProcessingFragment::class.simpleName
        private const val ANGLE_OF_ROTATION = 90

        fun newInstance(): ImageProcessingFragment {
            return ImageProcessingFragment()
        }
    }

    private var isInverted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        getScanActivity()?.croppedImage?.let {
            binding.imagePreview.setImageBitmap(it)
        }

        initListeners()
    }

    private fun initListeners() {
        binding.closeButton.setOnClickListener {
            closeFragment()
        }
        binding.confirmButton.setOnClickListener {
            selectFinalScannerResults()
        }
        binding.magicButton.setOnClickListener {
            applyGrayScaleFilter()
        }
        binding.rotateButton.setOnClickListener {
            rotateImage()
        }
    }

    private fun getScanActivity(): InternalScanActivity? {
        return activity as? InternalScanActivity
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun rotateImage() {
        Log.d(TAG, "ZDCrotate starts ${System.currentTimeMillis()}")
        val scanActivity = getScanActivity() ?: return
        showProgressBar()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val rotatedTransformed = scanActivity.transformedImage?.rotateBitmap(ANGLE_OF_ROTATION)
                val rotatedCropped = scanActivity.croppedImage?.rotateBitmap(ANGLE_OF_ROTATION)

                scanActivity.runOnUiThread {
                    if (isAdded && !isRemoving) {
                        hideProgressBar()
                        if (rotatedTransformed != null) {
                            scanActivity.transformedImage = rotatedTransformed
                        }
                        if (rotatedCropped != null) {
                            scanActivity.croppedImage = rotatedCropped
                        }
                        if (isInverted && scanActivity.transformedImage != null) {
                            binding.imagePreview.setImageBitmap(scanActivity.transformedImage)
                        } else {
                            binding.imagePreview.setImageBitmap(scanActivity.croppedImage)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rotating image", e)
                scanActivity.runOnUiThread {
                    if (isAdded && !isRemoving) {
                        hideProgressBar()
                    }
                }
            }
            Log.d(TAG, "ZDCrotate ends ${System.currentTimeMillis()}")
        }
    }

    private fun closeFragment() {
        getScanActivity()?.closeCurrentFragment()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun applyGrayScaleFilter() {
        Log.d(TAG, "ZDCgrayscale starts ${System.currentTimeMillis()}")
        val scanActivity = getScanActivity() ?: return
        val cropped = scanActivity.croppedImage
        if (cropped == null || cropped.isRecycled) {
            Log.e(TAG, "croppedImage is null or recycled")
            return
        }

        showProgressBar()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (!isInverted) {
                    val width = cropped.width
                    val height = cropped.height
                    if (width > 0 && height > 0) {
                        val bmpMonochrome = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmpMonochrome)
                        
                        val ma = ColorMatrix()
                        ma.setSaturation(0f)
                        
                        // Enhance contrast for document text readability
                        val contrast = 1.35f
                        val translate = (-0.5f * contrast + 0.5f) * 255f + 15f
                        val contrastMatrix = ColorMatrix(floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        contrastMatrix.postConcat(ma)

                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            colorFilter = ColorMatrixColorFilter(contrastMatrix)
                        }
                        
                        canvas.drawBitmap(cropped, 0f, 0f, paint)
                        scanActivity.transformedImage = bmpMonochrome

                        scanActivity.runOnUiThread {
                            if (isAdded && !isRemoving) {
                                hideProgressBar()
                                binding.imagePreview.setImageBitmap(scanActivity.transformedImage)
                            }
                        }
                    }
                } else {
                    scanActivity.runOnUiThread {
                        if (isAdded && !isRemoving) {
                            hideProgressBar()
                            binding.imagePreview.setImageBitmap(scanActivity.croppedImage)
                            scanActivity.transformedImage = null
                        }
                    }
                }
                isInverted = !isInverted
            } catch (e: Exception) {
                Log.e(TAG, "Error applying grayscale filter", e)
                scanActivity.runOnUiThread {
                    if (isAdded && !isRemoving) {
                        hideProgressBar()
                    }
                }
            }
            Log.d(TAG, "ZDCgrayscale ends ${System.currentTimeMillis()}")
        }
    }

    private fun selectFinalScannerResults() {
        getScanActivity()?.handleScanStep()
    }

    override fun configureEdgeToEdgeInsets(insets: WindowInsetsCompat) {
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

        with(binding) {
            imagePreview.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBarsInsets.top
            }

            bottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBarsInsets.bottom
            }
        }
    }
}
