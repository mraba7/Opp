package com.mraba7.deliverylocation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "delivery_location_prefs"
    private val KEY_ADDRESS = "address"

    private lateinit var setupLayout: LinearLayout
    private lateinit var sendLayout: LinearLayout

    private lateinit var addressInput: EditText
    private lateinit var photoPreview: ImageView
    private lateinit var choosePhotoBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var setupStatus: TextView

    private lateinit var savedPhotoView: ImageView
    private lateinit var savedAddressView: TextView
    private lateinit var sendBtn: Button
    private lateinit var waTextBtn: Button
    private lateinit var sendStatus: TextView
    private lateinit var editLink: TextView

    private val photoFile: File by lazy { File(filesDir, "home_photo.jpg") }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(photoFile).use { output ->
                        input.copyTo(output)
                    }
                }
                photoPreview.setImageURI(null)
                photoPreview.setImageURI(Uri.fromFile(photoFile))
                photoPreview.visibility = ImageView.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر تحميل الصورة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupLayout = findViewById(R.id.setupLayout)
        sendLayout = findViewById(R.id.sendLayout)

        addressInput = findViewById(R.id.addressInput)
        photoPreview = findViewById(R.id.photoPreview)
        choosePhotoBtn = findViewById(R.id.choosePhotoBtn)
        saveBtn = findViewById(R.id.saveBtn)
        setupStatus = findViewById(R.id.setupStatus)

        savedPhotoView = findViewById(R.id.savedPhotoView)
        savedAddressView = findViewById(R.id.savedAddressView)
        sendBtn = findViewById(R.id.sendBtn)
        waTextBtn = findViewById(R.id.waTextBtn)
        sendStatus = findViewById(R.id.sendStatus)
        editLink = findViewById(R.id.editLink)

        choosePhotoBtn.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        saveBtn.setOnClickListener {
            val address = addressInput.text.toString().trim()
            if (address.isEmpty()) {
                setupStatus.text = "الرجاء كتابة العنوان أولاً"
                return@setOnClickListener
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_ADDRESS, address)
                .apply()
            showSendScreen(address)
        }

        editLink.setOnClickListener {
            val address = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ADDRESS, "") ?: ""
            showSetupScreen(address)
        }

        sendBtn.setOnClickListener { sendLocation() }
        waTextBtn.setOnClickListener { sendTextOnly() }

        val savedAddress = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ADDRESS, "")
        if (!savedAddress.isNullOrEmpty()) {
            showSendScreen(savedAddress)
        } else {
            showSetupScreen("")
        }
    }

    private fun showSetupScreen(prefillAddress: String) {
        setupLayout.visibility = LinearLayout.VISIBLE
        sendLayout.visibility = LinearLayout.GONE
        addressInput.setText(prefillAddress)
        if (photoFile.exists()) {
            photoPreview.setImageURI(Uri.fromFile(photoFile))
            photoPreview.visibility = ImageView.VISIBLE
        }
    }

    private fun showSendScreen(address: String) {
        setupLayout.visibility = LinearLayout.GONE
        sendLayout.visibility = LinearLayout.VISIBLE
        savedAddressView.text = address
        if (photoFile.exists()) {
            savedPhotoView.setImageURI(Uri.fromFile(photoFile))
        }
        sendStatus.text = ""
    }

    private fun sendLocation() {
        val address = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ADDRESS, "") ?: ""
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, "موقعي: $address")

        if (photoFile.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = "image/jpeg"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
        }

        intent.setPackage("com.whatsapp")
        try {
            startActivity(intent)
            sendStatus.text = "تم فتح واتساب، اختر محادثة المندوب"
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                startActivity(Intent.createChooser(intent, "إرسال عبر"))
            } catch (e2: Exception) {
                sendStatus.text = "لم يتم العثور على واتساب على الجهاز"
            }
        }
    }

    private fun sendTextOnly() {
        val address = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ADDRESS, "") ?: ""
        val encoded = Uri.encode("موقعي: $address")
        val uri = Uri.parse("https://wa.me/?text=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
        sendStatus.text = "تم فتح واتساب، أرفق الصورة يدوياً إذا لزم"
    }
}
