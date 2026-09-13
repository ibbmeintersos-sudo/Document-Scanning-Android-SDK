package com.zynksoftware.documentscannersample

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUriUtils {
    
    fun copyFileToSaveLocation(context: Context, sourceFile: File, prefix: String): Uri? {
        val date = Date()
        val formatter = SimpleDateFormat("dd_MM_yyyy_HH_mm_ss", Locale.getDefault())
        val dateFormatted = formatter.format(date)
        val fileName = "${prefix}_${dateFormatted}.pdf"
        val mimeType = "application/pdf"

        val customTreeUriStr = SettingsManager.getCustomTreeUri(context)
        if (customTreeUriStr != null) {
            try {
                val treeUri = Uri.parse(customTreeUriStr)
                val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                if (pickedDir != null && pickedDir.exists() && pickedDir.canWrite()) {
                    val targetFile = pickedDir.createFile(mimeType, fileName)
                    if (targetFile != null) {
                        context.contentResolver.openOutputStream(targetFile.uri)?.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(out)
                            }
                            out.flush()
                        }
                        return targetFile.uri
                    }
                }
            } catch (e: Exception) {
                Log.e("FileUriUtils", "Failed saving to custom tree uri: $customTreeUriStr", e)
            }
        }

        val folderType = SettingsManager.getFolderType(context)
        val subfolderName = SettingsManager.getSubfolderName(context)

        val relativePath = if (subfolderName.isNotEmpty()) {
            "$folderType/$subfolderName"
        } else {
            folderType
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver: ContentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
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
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                }
                return pdfUri
            }
        } else {
            val targetDir = File(Environment.getExternalStorageDirectory(), relativePath)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)
            FileOutputStream(targetFile).use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
                out.flush()
            }
            return Uri.fromFile(targetFile)
        }
        return null
    }
}
