package com.zynksoftware.documentscannersample

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Environment.DIRECTORY_DCIM
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.fondesa.kpermissions.allGranted
import com.fondesa.kpermissions.allShouldShowRationale
import com.fondesa.kpermissions.extension.permissionsBuilder
import com.fondesa.kpermissions.extension.send
import com.zynksoftware.documentscanner.ScanActivity
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel
import com.zynksoftware.documentscanner.model.ScanType
import com.zynksoftware.documentscanner.model.ScannerResults
import com.zynksoftware.documentscannersample.adapters.ImageAdapter
import com.zynksoftware.documentscannersample.adapters.ImageAdapterListener
import com.zynksoftware.documentscannersample.databinding.AppScanActivityLayoutBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppScanActivity : ScanActivity(), ImageAdapterListener {
    private lateinit var binding: AppScanActivityLayoutBinding

    private enum class SaveFormat {
        IMAGE, PDF
    }

    companion object {
        private val TAG = AppScanActivity::class.simpleName

        fun start(context: Context, scanType: ScanType = ScanType.DOCUMENT) {
            val intent = Intent(context, AppScanActivity::class.java).apply {
                putExtra(EXTRA_SCAN_TYPE, scanType.name)
            }
            context.startActivity(intent)
        }
    }

    private var alertDialogBuilder: android.app.AlertDialog.Builder? = null
    private var alertDialog: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AppScanActivityLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        addFragmentContentLayout()
        setupEdgeToEdge()
    }

    override fun onError(error: DocumentScannerErrorModel) {
        showAlertDialog(
            getString(R.string.error_label),
            error.errorMessage?.error,
            getString(R.string.ok_label)
        )
    }

    override fun onSuccess(scannerResults: ScannerResults) {
        initViewPager(scannerResults)
    }

    override fun onClose() {
        Log.d(TAG, "onClose")
        finish()
    }

    override fun onSaveButtonClicked(image: File) {
        showSaveOptionsDialog(image)
    }

    private fun showSaveOptionsDialog(image: File) {
        val options = arrayOf("🖼️ حفظ كصورة (JPG)", "📄 حفظ كمستند (PDF)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("اختر صيغة الحفظ")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkForStoragePermissions(image, SaveFormat.IMAGE)
                    1 -> checkForStoragePermissions(image, SaveFormat.PDF)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun checkForStoragePermissions(image: File, format: SaveFormat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (format == SaveFormat.IMAGE) {
                saveImage(image)
            } else {
                savePdf(image)
            }
            return
        }

        permissionsBuilder(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
            .build()
            .send { result ->
                if (result.allGranted()) {
                    if (format == SaveFormat.IMAGE) {
                        saveImage(image)
                    } else {
                        savePdf(image)
                    }
                } else if (result.allShouldShowRationale()) {
                    onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.STORAGE_PERMISSION_REFUSED_WITHOUT_NEVER_ASK_AGAIN))
                } else {
                    onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.STORAGE_PERMISSION_REFUSED_GO_TO_SETTINGS))
                }
            }
    }

    private fun saveToCustomTreeUri(fileName: String, mimeType: String, writeBlock: (OutputStream) -> Unit): Boolean {
        val customTreeUriStr = SettingsManager.getCustomTreeUri(this) ?: return false
        try {
            val treeUri = Uri.parse(customTreeUriStr)
            val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
            if (pickedDir != null && pickedDir.exists() && pickedDir.canWrite()) {
                val targetFile = pickedDir.createFile(mimeType, fileName)
                if (targetFile != null) {
                    contentResolver.openOutputStream(targetFile.uri)?.use { out ->
                        writeBlock(out)
                        out.flush()
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving to custom tree uri: $customTreeUriStr", e)
        }
        return false
    }

    private fun saveImage(image: File) {
        showProgressBar()

        val date = Date()
        val formatter = SimpleDateFormat("dd_MM_yyyy_HH_mm_ss", Locale.getDefault())
        val dateFormatted = formatter.format(date)
        val fileName = "scan_${dateFormatted}.jpg"

        if (saveToCustomTreeUri(fileName, "image/jpeg") { out -> out.write(image.readBytes()) }) {
            hideProgressBar()
            val displayPath = SettingsManager.getDisplaySavePath(this)
            showAlertDialog(
                getString(R.string.photo_saved),
                "تم حفظ الصورة بنجاح في المجلد المخصص:\n$displayPath/$fileName",
                getString(R.string.ok_label)
            )
            return
        }

        val folderType = SettingsManager.getFolderType(this)
        val subfolderName = SettingsManager.getSubfolderName(this)

        val relativePath = if (subfolderName.isNotEmpty()) {
            "$folderType/$subfolderName"
        } else {
            folderType
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver: ContentResolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    resolver.openOutputStream(imageUri)?.use { out ->
                        out.write(image.readBytes())
                        out.flush()
                    }
                }
            } else {
                val targetDir = File(Environment.getExternalStorageDirectory(), relativePath)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val targetFile = File(targetDir, fileName)
                FileOutputStream(targetFile).use { out ->
                    out.write(image.readBytes())
                    out.flush()
                }
            }
            hideProgressBar()
            showAlertDialog(
                getString(R.string.photo_saved),
                "تم حفظ الصورة بنجاح في:\n$relativePath/$fileName",
                getString(R.string.ok_label)
            )
        } catch (e: Exception) {
            hideProgressBar()
            showAlertDialog(
                getString(R.string.error_label),
                "حدث خطأ أثناء الحفظ: ${e.localizedMessage}",
                getString(R.string.ok_label)
            )
        }
    }

    private fun savePdf(image: File) {
        showProgressBar()

        val date = Date()
        val formatter = SimpleDateFormat("dd_MM_yyyy_HH_mm_ss", Locale.getDefault())
        val dateFormatted = formatter.format(date)
        val pdfFileName = "scan_${dateFormatted}.pdf"

        try {
            val bitmap = BitmapFactory.decodeFile(image.absolutePath)
            if (bitmap == null) {
                hideProgressBar()
                showAlertDialog(getString(R.string.error_label), "تعذر قراءة الصورة لإنشاء ملف PDF", getString(R.string.ok_label))
                return
            }

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDocument.finishPage(page)

            if (saveToCustomTreeUri(pdfFileName, "application/pdf") { out -> pdfDocument.writeTo(out) }) {
                pdfDocument.close()
                hideProgressBar()
                val displayPath = SettingsManager.getDisplaySavePath(this)
                showAlertDialog(
                    getString(R.string.photo_saved),
                    "تم حفظ مستند PDF بنجاح في المجلد المخصص:\n$displayPath/$pdfFileName",
                    getString(R.string.ok_label)
                )
                return
            }

            val folderType = SettingsManager.getFolderType(this)
            val subfolderName = SettingsManager.getSubfolderName(this)

            val relativePath = if (subfolderName.isNotEmpty()) {
                "$folderType/$subfolderName"
            } else {
                folderType
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver: ContentResolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, pdfFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                val pdfUri: Uri? = try {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                } catch (e: Exception) {
                    null
                } ?: try {
                    resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                } catch (e: Exception) {
                    null
                }

                if (pdfUri != null) {
                    resolver.openOutputStream(pdfUri)?.use { out ->
                        pdfDocument.writeTo(out)
                        out.flush()
                    }
                }
            } else {
                val targetDir = File(Environment.getExternalStorageDirectory(), relativePath)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val targetFile = File(targetDir, pdfFileName)
                FileOutputStream(targetFile).use { out ->
                    pdfDocument.writeTo(out)
                    out.flush()
                }
            }
            pdfDocument.close()

            hideProgressBar()
            showAlertDialog(
                getString(R.string.photo_saved),
                "تم حفظ مستند PDF بنجاح في:\n$relativePath/$pdfFileName",
                getString(R.string.ok_label)
            )
        } catch (e: Exception) {
            hideProgressBar()
            showAlertDialog(
                getString(R.string.error_label),
                "حدث خطأ أثناء حفظ ملف PDF: ${e.localizedMessage}",
                getString(R.string.ok_label)
            )
        }
    }

    private fun showProgressBar() {
        binding.progressLayoutApp.isVisible = true
    }

    private fun hideProgressBar() {
        binding.progressLayoutApp.isVisible = false
    }

    private fun initViewPager(scannerResults: ScannerResults) {
        val fileList = ArrayList<File>()

        scannerResults.originalImageFile?.let {
            Log.d(TAG, "ZDCoriginalPhotoFile size ${it.sizeInMb}")
        }

        scannerResults.croppedImageFile?.let {
            Log.d(TAG, "ZDCcroppedPhotoFile size ${it.sizeInMb}")
        }

        scannerResults.transformedImageFile?.let {
            Log.d(TAG, "ZDCtransformedPhotoFile size ${it.sizeInMb}")
        }

        scannerResults.originalImageFile?.let { fileList.add(it) }
        scannerResults.transformedImageFile?.let { fileList.add(it) }
        scannerResults.croppedImageFile?.let { fileList.add(it) }
        val targetAdapter = ImageAdapter(this, fileList, this)
        binding.viewPagerTwo.adapter = targetAdapter
        binding.viewPagerTwo.isUserInputEnabled = false

        binding.previousButton.setOnClickListener {
            binding.viewPagerTwo.currentItem -= 1
            binding.nextButton.isVisible = true
            if (binding.viewPagerTwo.currentItem == 0) {
                binding.previousButton.isVisible = false
            }
        }

        binding.nextButton.setOnClickListener {
            binding.viewPagerTwo.currentItem += 1
            binding.previousButton.isVisible = true
            if (binding.viewPagerTwo.currentItem == fileList.size - 1) {
                binding.nextButton.isVisible = false
            }
        }
    }

    private fun showAlertDialog(title: String?, message: String?, buttonMessage: String) {
        alertDialogBuilder = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonMessage) { _, _ ->

            }
        alertDialog?.dismiss()
        alertDialog = alertDialogBuilder?.create()
        alertDialog?.setCanceledOnTouchOutside(false)
        alertDialog?.show()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            with(binding) {
                viewPagerTwo.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = systemBarsInsets.top
                }

                bottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = systemBarsInsets.bottom
                }
            }

            insets
        }
    }

    private val File.size get() = if (!exists()) 0.0 else length().toDouble()
    private val File.sizeInKb get() = size / 1024
    private val File.sizeInMb get() = sizeInKb / 1024
}
