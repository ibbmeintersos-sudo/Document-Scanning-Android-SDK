package com.zynksoftware.documentscannersample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.zynksoftware.documentscanner.model.ScanType
import com.zynksoftware.documentscannersample.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to take persistable URI permission", e)
            }
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
            val folderName = docFile?.name ?: SettingsManager.formatUriToPath(uri.toString())
            SettingsManager.setCustomTreeUri(this, uri.toString(), "📁 $folderName")
            updateSavePathDisplay()
            Toast.makeText(this, "تم تحديد المجلد بنجاح: $folderName", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initListeners()
        updateSavePathDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateSavePathDisplay()
    }

    private fun updateSavePathDisplay() {
        val currentPath = SettingsManager.getDisplaySavePath(this)
        binding.tvCurrentSavePath.text = currentPath
    }

    private fun initListeners() {
        binding.cardScanButton.setOnClickListener {
            AppScanActivity.start(this, ScanType.ID_CARD)
        }
        binding.docScanButton.setOnClickListener {
            AppScanActivity.start(this, ScanType.DOCUMENT)
        }
        binding.importDocButton.setOnClickListener {
            startActivity(Intent(this, ImportDocumentActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }
        binding.saveLocationCard.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun showSettingsDialog() {
        val currentFolderType = SettingsManager.getFolderType(this)
        val currentSubfolder = SettingsManager.getSubfolderName(this)
        val customUri = SettingsManager.getCustomTreeUri(this)

        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val btnPickSystemFolder = dialogView.findViewById<android.view.View>(R.id.btnPickSystemFolder)
        val rgDefaultFolders = dialogView.findViewById<RadioGroup>(R.id.rgDefaultFolders)
        val rbDocuments = dialogView.findViewById<RadioButton>(R.id.rbDocuments)
        val rbPictures = dialogView.findViewById<RadioButton>(R.id.rbPictures)
        val rbDcim = dialogView.findViewById<RadioButton>(R.id.rbDcim)
        val rbDownload = dialogView.findViewById<RadioButton>(R.id.rbDownload)
        val etSubfolder = dialogView.findViewById<EditText>(R.id.etSubfolder)

        etSubfolder.setText(currentSubfolder)

        if (customUri == null) {
            when (currentFolderType) {
                SettingsManager.FOLDER_DOCUMENTS -> rbDocuments.isChecked = true
                SettingsManager.FOLDER_PICTURES -> rbPictures.isChecked = true
                SettingsManager.FOLDER_DCIM -> rbDcim.isChecked = true
                SettingsManager.FOLDER_DOWNLOAD -> rbDownload.isChecked = true
                else -> rbDocuments.isChecked = true
            }
        }

        var dialog: AlertDialog? = null

        btnPickSystemFolder.setOnClickListener {
            dialog?.dismiss()
            selectFolderLauncher.launch(null)
        }

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("حفظ") { _, _ ->
                val selectedType = when (rgDefaultFolders.checkedRadioButtonId) {
                    R.id.rbPictures -> SettingsManager.FOLDER_PICTURES
                    R.id.rbDcim -> SettingsManager.FOLDER_DCIM
                    R.id.rbDownload -> SettingsManager.FOLDER_DOWNLOAD
                    else -> SettingsManager.FOLDER_DOCUMENTS
                }
                val subfolderName = etSubfolder.text.toString().trim()

                SettingsManager.setSaveLocation(this, selectedType, subfolderName)
                updateSavePathDisplay()
                Toast.makeText(this, "تم تحديث مجلد الحفظ إلى: ${SettingsManager.getDisplaySavePath(this)}", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun setupEdgeToEdge() {
        enableEdgeToEdge()
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}