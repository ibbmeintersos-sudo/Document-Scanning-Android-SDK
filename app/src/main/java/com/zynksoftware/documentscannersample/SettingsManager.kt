package com.zynksoftware.documentscannersample

import android.content.Context
import android.net.Uri

object SettingsManager {
    private const val PREFS_NAME = "doc_scanner_settings"
    private const val KEY_FOLDER_TYPE = "folder_type"
    private const val KEY_SUBFOLDER_NAME = "subfolder_name"
    private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
    private const val KEY_CUSTOM_TREE_NAME = "custom_tree_name"

    const val FOLDER_DOCUMENTS = "Documents"
    const val FOLDER_PICTURES = "Pictures"
    const val FOLDER_DCIM = "DCIM"
    const val FOLDER_DOWNLOAD = "Download"

    fun getFolderType(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FOLDER_TYPE, FOLDER_DOCUMENTS) ?: FOLDER_DOCUMENTS
    }

    fun getSubfolderName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SUBFOLDER_NAME, "DocumentScanner") ?: "DocumentScanner"
    }

    fun setSaveLocation(context: Context, folderType: String, subfolderName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_FOLDER_TYPE, folderType)
            .putString(KEY_SUBFOLDER_NAME, subfolderName.trim())
            .remove(KEY_CUSTOM_TREE_URI)
            .remove(KEY_CUSTOM_TREE_NAME)
            .apply()
    }

    fun getCustomTreeUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_TREE_URI, null)
    }

    fun setCustomTreeUri(context: Context, uriString: String, folderDisplayName: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CUSTOM_TREE_URI, uriString)
            .putString(KEY_CUSTOM_TREE_NAME, folderDisplayName ?: formatUriToPath(uriString))
            .apply()
    }

    fun clearCustomTreeUri(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_CUSTOM_TREE_URI)
            .remove(KEY_CUSTOM_TREE_NAME)
            .apply()
    }

    fun formatUriToPath(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            val path = Uri.decode(uri.lastPathSegment) ?: ""
            if (path.startsWith("primary:")) {
                path.replaceFirst("primary:", "الهاتف: ")
            } else if (path.contains(":")) {
                path.substringAfter(":")
            } else if (path.isNotEmpty()) {
                path
            } else {
                "مجلد مخصص من الهاتف"
            }
        } catch (e: Exception) {
            "مجلد مخصص من الهاتف"
        }
    }

    fun getDisplaySavePath(context: Context): String {
        val customUri = getCustomTreeUri(context)
        if (!customUri.isNullOrEmpty()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val customName = prefs.getString(KEY_CUSTOM_TREE_NAME, null)
            return customName ?: formatUriToPath(customUri)
        }
        val type = getFolderType(context)
        val subfolder = getSubfolderName(context)
        return if (subfolder.isEmpty()) type else "$type/$subfolder"
    }
}

