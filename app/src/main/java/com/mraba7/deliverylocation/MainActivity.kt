package com.mraba7.deliverylocation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
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
    private val KEY_MAPS_LINK = "maps_link"
    private val KEY_NATIONAL_ADDRESS = "national_address"
    private val KEY_DROP_OFF = "drop_off"
    private val MAX_PHOTOS = 6

    private lateinit var setupLayout: LinearLayout
    private lateinit var sendLayout: LinearLayout

    private lateinit var mapsLinkInput: EditText
    private lateinit var nationalAddressInput: EditText
    private lateinit var dropOffInput: EditText
    private lateinit var photoThumbsRow: LinearLayout
    private lateinit var choosePhotoBtn: Button
    private lateinit var clearPhotosBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var setupStatus: TextView

    private lateinit var savedPhotoThumbsRow: LinearLayout
    private lateinit var savedDetailsView: TextView
    private lateinit var sendBtn: Button
    private lateinit var waTextBtn: Button
    private lateinit var sendStatus: TextView
    private lateinit var editLink: TextView

    private fun photosDir(): File {
        val dir = File(filesDir, "photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun photoFiles(): List<File> {
        return photosDir().listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val existingCount = photoFiles().size
            var index = existingCount
            for (uri in uris) {
                if (index >= MAX_PHOTOS) {
                    Toast.makeText(this, "الحد الأقصى $MAX_PHOTOS صور", Toast.LENGTH_SHORT).show()
                    break
                }
                try {
                    val target = File(photosDir(), "photo_$index.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    index++
                } catch (e: Exception) {
                    // تجاهل الصورة اللي فشلت ونكمل الباقي
                }
            }
            renderThumbs(photoThumbsRow, photoFiles())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupLayout = findViewById(R.id.setupLayout)
        sendLayout = findViewById(R.id.sendLayout)

        mapsLinkInput = findViewById(R.id.mapsLinkInput)
        nationalAddressInput = findViewById(R.id.nationalAddressInput)
        dropOffInput = findViewById(R.id.dropOffInput)
        photoThumbsRow = findViewById(R.id.photoThumbsRow)
        choosePhotoBtn = findViewById(R.id.choosePhotoBtn)
        clearPhotosBtn = findViewById(R.id.clearPhotosBtn)
        saveBtn = findViewById(R.id.saveBtn)
        setupStatus = findViewById(R.id.setupStatus)

        savedPhotoThumbsRow = findViewById(R.id.savedPhotoThumbsRow)
        savedDetailsView = findViewById(R.id.savedDetailsView)
        sendBtn = findViewById(R.id.sendBtn)
        waTextBtn = findViewById(R.id.waTextBtn)
        sendStatus = findViewById(R.id.sendStatus)
        editLink = findViewById(R.id.editLink)

        choosePhotoBtn.setOnClickListener {
            pickImagesLauncher.launch("image/*")
        }

        clearPhotosBtn.setOnClickListener {
            photoFiles().forEach { it.delete() }
            renderThumbs(photoThumbsRow, emptyList())
        }

        saveBtn.setOnClickListener {
            val mapsLink = mapsLinkInput.text.toString().trim()
            val nationalAddress = nationalAddressInput.text.toString().trim()
            val dropOff = dropOffInput.text.toString().trim()

            if (mapsLink.isEmpty() && nationalAddress.isEmpty()) {
                setupStatus.text = "الرجاء تعبئة رابط الموقع أو العنوان الوطني على الأقل"
                return@setOnClickListener
            }

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_MAPS_LINK, mapsLink)
                .putString(KEY_NATIONAL_ADDRESS, nationalAddress)
                .putString(KEY_DROP_OFF, dropOff)
                .apply()

            showSendScreen()
        }

        editLink.setOnClickListener { showSetupScreen() }

        sendBtn.setOnClickListener { sendLocation() }
        waTextBtn.setOnClickListener { sendTextOnly() }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasSavedData = !prefs.getString(KEY_MAPS_LINK, "").isNullOrEmpty() ||
                !prefs.getString(KEY_NATIONAL_ADDRESS, "").isNullOrEmpty()

        if (hasSavedData) {
            showSendScreen()
        } else {
            showSetupScreen()
        }
    }

    private fun renderThumbs(container: LinearLayout, files: List<File>) {
        container.removeAllViews()
        val sizePx = (72 * resources.displayMetrics.density).toInt()
        val marginPx = (6 * resources.displayMetrics.density).toInt()
        for (file in files) {
            val img = ImageView(this)
            val params = LinearLayout.LayoutParams(sizePx, sizePx)
            params.marginEnd = marginPx
            img.layoutParams = params
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setImageURI(Uri.fromFile(file))
            container.addView(img)
        }
        if (files.isEmpty()) {
            val empty = TextView(this)
            empty.text = "لا توجد صور بعد"
            empty.setTextColor(resources.getColor(R.color.muted, theme))
            empty.textSize = 12.5f
            container.addView(empty)
        }
    }

    private fun showSetupScreen() {
        setupLayout.visibility = LinearLayout.VISIBLE
        sendLayout.visibility = LinearLayout.GONE

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        mapsLinkInput.setText(prefs.getString(KEY_MAPS_LINK, ""))
        nationalAddressInput.setText(prefs.getString(KEY_NATIONAL_ADDRESS, ""))
        dropOffInput.setText(prefs.getString(KEY_DROP_OFF, ""))
        renderThumbs(photoThumbsRow, photoFiles())
    }

    private fun showSendScreen() {
        setupLayout.visibility = LinearLayout.GONE
        sendLayout.visibility = LinearLayout.VISIBLE

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mapsLink = prefs.getString(KEY_MAPS_LINK, "") ?: ""
        val nationalAddress = prefs.getString(KEY_NATIONAL_ADDRESS, "") ?: ""
        val dropOff = prefs.getString(KEY_DROP_OFF, "") ?: ""

        val details = StringBuilder()
        if (mapsLink.isNotEmpty()) details.append("📍 الموقع: $mapsLink\n")
        if (nationalAddress.isNotEmpty()) details.append("🏷️ العنوان الوطني: $nationalAddress\n")
        if (dropOff.isNotEmpty()) details.append("📦 مكان وضع الشحنة: $dropOff")

        savedDetailsView.text = details.toString().trim()
        renderThumbs(savedPhotoThumbsRow, photoFiles())
        sendStatus.text = ""
    }

    private fun buildMessageText(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mapsLink = prefs.getString(KEY_MAPS_LINK, "") ?: ""
        val nationalAddress = prefs.getString(KEY_NATIONAL_ADDRESS, "") ?: ""
        val dropOff = prefs.getString(KEY_DROP_OFF, "") ?: ""

        val sb = StringBuilder()
        if (mapsLink.isNotEmpty()) sb.append("📍 الموقع: $mapsLink\n")
        if (nationalAddress.isNotEmpty()) sb.append("🏷️ العنوان الوطني: $nationalAddress\n")
        if (dropOff.isNotEmpty()) sb.append("📦 مكان وضع الشحنة: $dropOff")
        return sb.toString().trim()
    }

    private fun sendLocation() {
        val text = buildMessageText()
        val files = photoFiles()

        val intent: Intent
        if (files.isEmpty()) {
            intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, text)
        } else if (files.size == 1) {
            intent = Intent(Intent.ACTION_SEND)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", files[0])
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(Intent.EXTRA_TEXT, text)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            })
            intent.type = "image/jpeg"
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.putExtra(Intent.EXTRA_TEXT, text)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        val text = buildMessageText()
        val encoded = Uri.encode(text)
        val uri = Uri.parse("https://wa.me/?text=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
        sendStatus.text = "تم فتح واتساب، أرفق الصور يدوياً إذا لزم"
    }
}
