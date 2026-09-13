package com.zynksoftware.documentscannersample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class ImportDocumentActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    // Register launchers
    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            mergeImagesToPdf(uris)
        }
    }

    private val pickMultiplePdfsLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            mergePdfs(uris)
        }
    }

    private val pickSinglePdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            promptForPagesToExtract(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_document)
        PDFBoxResourceLoader.init(applicationContext)

        progressBar = findViewById(R.id.progressBar)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnImagesToPdf).setOnClickListener {
            pickImagesLauncher.launch("image/*")
        }

        findViewById<View>(R.id.btnMergePdf).setOnClickListener {
            pickMultiplePdfsLauncher.launch("application/pdf")
        }

        findViewById<View>(R.id.btnExtractPdf).setOnClickListener {
            pickSinglePdfLauncher.launch("application/pdf")
        }
    }

    private fun setupEdgeToEdge() {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }

    private fun mergeImagesToPdf(uris: List<Uri>) {
        setLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pdfDocument = PdfDocument()
                for ((index, uri) in uris.withIndex()) {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (bitmap != null) {
                        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        pdfDocument.finishPage(page)
                    }
                }

                val tempFile = File(cacheDir, "MergedImages_${UUID.randomUUID()}.pdf")
                val outputStream = FileOutputStream(tempFile)
                pdfDocument.writeTo(outputStream)
                pdfDocument.close()
                outputStream.close()

                val savedUri = FileUriUtils.copyFileToSaveLocation(this@ImportDocumentActivity, tempFile, "MergedImages")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportDocumentActivity, "تم حفظ المستند بنجاح!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ImportDocumentActivity", "Error merging images", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportDocumentActivity, "حدث خطأ أثناء دمج الصور", Toast.LENGTH_LONG).show()
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private fun mergePdfs(uris: List<Uri>) {
        setLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val merger = PDFMergerUtility()
                val tempFile = File(cacheDir, "MergedPDF_${UUID.randomUUID()}.pdf")
                merger.destinationFileName = tempFile.absolutePath

                val tempFiles = mutableListOf<File>()
                for (uri in uris) {
                    val stream = contentResolver.openInputStream(uri)
                    val temp = File.createTempFile("temp_pdf", ".pdf", cacheDir)
                    stream?.use { input ->
                        temp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFiles.add(temp)
                    merger.addSource(temp)
                }

                merger.mergeDocuments(null)

                // Cleanup temp inputs
                tempFiles.forEach { it.delete() }

                val savedUri = FileUriUtils.copyFileToSaveLocation(this@ImportDocumentActivity, tempFile, "MergedPDF")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportDocumentActivity, "تم دمج وحفظ الملفات بنجاح!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ImportDocumentActivity", "Error merging PDFs", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportDocumentActivity, "حدث خطأ أثناء دمج الملفات", Toast.LENGTH_LONG).show()
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private fun promptForPagesToExtract(uri: Uri) {
        val input = EditText(this).apply {
            hint = "مثال: 1, 3, 5-7"
            setTextColor(android.graphics.Color.BLACK)
        }
        AlertDialog.Builder(this)
            .setTitle("استخراج الصفحات")
            .setMessage("أدخل أرقام الصفحات المراد استخراجها (مثال: 1, 2, 4 أو 1-3)")
            .setView(input)
            .setPositiveButton("استخراج") { _, _ ->
                val pagesStr = input.text.toString()
                extractPagesFromPdf(uri, pagesStr)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun extractPagesFromPdf(uri: Uri, pagesStr: String) {
        setLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pagesToExtract = parsePageNumbers(pagesStr)
                if (pagesToExtract.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImportDocumentActivity, "صيغة أرقام الصفحات غير صحيحة", Toast.LENGTH_SHORT).show()
                    }
                    setLoading(false)
                    return@launch
                }

                val stream = contentResolver.openInputStream(uri)
                val document = PDDocument.load(stream)
                val totalPages = document.numberOfPages

                val newDocument = PDDocument()
                for (pageIndex in pagesToExtract) {
                    if (pageIndex in 1..totalPages) {
                        newDocument.addPage(document.getPage(pageIndex - 1))
                    }
                }

                val tempFile = File(cacheDir, "ExtractedPages_${UUID.randomUUID()}.pdf")
                newDocument.save(tempFile)
                newDocument.close()
                document.close()

                val savedUri = FileUriUtils.copyFileToSaveLocation(this@ImportDocumentActivity, tempFile, "ExtractedPages")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportDocumentActivity, "تم استخراج الصفحات وحفظ الملف بنجاح!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ImportDocumentActivity", "Error extracting pages", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImportDocumentActivity, "حدث خطأ أثناء استخراج الصفحات", Toast.LENGTH_LONG).show()
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private fun parsePageNumbers(input: String): List<Int> {
        val pages = mutableSetOf<Int>()
        val parts = input.split(",")
        for (part in parts) {
            val trimPart = part.trim()
            if (trimPart.contains("-")) {
                val range = trimPart.split("-")
                if (range.size == 2) {
                    val start = range[0].trim().toIntOrNull()
                    val end = range[1].trim().toIntOrNull()
                    if (start != null && end != null && start <= end) {
                        for (i in start..end) {
                            pages.add(i)
                        }
                    }
                }
            } else {
                val page = trimPart.toIntOrNull()
                if (page != null) {
                    pages.add(page)
                }
            }
        }
        return pages.toList().sorted()
    }

    private fun setLoading(isLoading: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }
}
