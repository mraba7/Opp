package com.mraba7.deliverylocation

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "delivery_location_prefs"
    private val KEY_PROFILES = "profiles_list"
    private val KEY_CURRENT_PROFILE = "current_profile"
    private val MAX_PHOTOS = 6
    private val DEFAULT_PROFILE = "المنزل"

    private lateinit var profileSpinner: Spinner
    private lateinit var addProfileBtn: Button
    private lateinit var deleteProfileBtn: Button

    private lateinit var setupLayout: LinearLayout
    private lateinit var sendLayout: LinearLayout

    private lateinit var mapsLinkInput: EditText
    private lateinit var nationalAddressInput: EditText
    private lateinit var buildingNumberInput: EditText
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

    private var currentProfile: String = DEFAULT_PROFILE
    private var suppressSpinnerCallback = false

    // ---------- إدارة البروفايلات ----------

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun getProfiles(): MutableList<String> {
        val raw = prefs().getString(KEY_PROFILES, "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        return raw.split("|||").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveProfiles(list: List<String>) {
        prefs().edit().putString(KEY_PROFILES, list.joinToString("|||")).apply()
    }

    private fun keyFor(field: String, profile: String) = "${field}__${profile}"

    private fun profilePhotosDir(profile: String): File {
        val dir = File(filesDir, "photos/$profile")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun profilePhotoFiles(profile: String): List<File> {
        return profilePhotosDir(profile).listFiles()?.sortedBy { it.name } ?: emptyList()
    }

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val existingCount = profilePhotoFiles(currentProfile).size
            var index = existingCount
            for (uri in uris) {
                if (index >= MAX_PHOTOS) {
                    Toast.makeText(this, "الحد الأقصى $MAX_PHOTOS صور", Toast.LENGTH_SHORT).show()
                    break
                }
                try {
                    val target = File(profilePhotosDir(currentProfile), "photo_$index.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    index++
                } catch (e: Exception) {
                    // تجاهل الصورة اللي فشلت ونكمل الباقي
                }
            }
            renderThumbs(photoThumbsRow, profilePhotoFiles(currentProfile))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        profileSpinner = findViewById(R.id.profileSpinner)
        addProfileBtn = findViewById(R.id.addProfileBtn)
        deleteProfileBtn = findViewById(R.id.deleteProfileBtn)

        setupLayout = findViewById(R.id.setupLayout)
        sendLayout = findViewById(R.id.sendLayout)

        mapsLinkInput = findViewById(R.id.mapsLinkInput)
        nationalAddressInput = findViewById(R.id.nationalAddressInput)
        buildingNumberInput = findViewById(R.id.buildingNumberInput)
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

        // تجهيز البروفايلات لأول مرة
        var profiles = getProfiles()
        if (profiles.isEmpty()) {
            profiles = mutableListOf(DEFAULT_PROFILE)
            saveProfiles(profiles)
            prefs().edit().putString(KEY_CURRENT_PROFILE, DEFAULT_PROFILE).apply()
        }
        currentProfile = prefs().getString(KEY_CURRENT_PROFILE, profiles.first()) ?: profiles.first()
        if (!profiles.contains(currentProfile)) currentProfile = profiles.first()

        setupProfileSpinner(profiles)

        choosePhotoBtn.setOnClickListener {
            pickImagesLauncher.launch("image/*")
        }

        clearPhotosBtn.setOnClickListener {
            profilePhotoFiles(currentProfile).forEach { it.delete() }
            renderThumbs(photoThumbsRow, emptyList())
        }

        saveBtn.setOnClickListener {
            val mapsLink = mapsLinkInput.text.toString().trim()
            val nationalAddress = nationalAddressInput.text.toString().trim()
            val buildingNumber = buildingNumberInput.text.toString().trim()
            val dropOff = dropOffInput.text.toString().trim()

            if (mapsLink.isEmpty() && nationalAddress.isEmpty()) {
                setupStatus.text = "الرجاء تعبئة رابط الموقع أو العنوان الوطني على الأقل"
                return@setOnClickListener
            }

            prefs().edit()
                .putString(keyFor("maps_link", currentProfile), mapsLink)
                .putString(keyFor("national_address", currentProfile), nationalAddress)
                .putString(keyFor("building_number", currentProfile), buildingNumber)
                .putString(keyFor("drop_off", currentProfile), dropOff)
                .apply()

            showSendScreen()
        }

        editLink.setOnClickListener { showSetupScreen() }

        addProfileBtn.setOnClickListener { promptNewProfile() }
        deleteProfileBtn.setOnClickListener { confirmDeleteProfile() }

        sendBtn.setOnClickListener { sendLocation() }
        waTextBtn.setOnClickListener { sendTextOnly() }

        refreshScreenForCurrentProfile()
    }

    private fun setupProfileSpinner(profiles: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profiles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = adapter
        val idx = profiles.indexOf(currentProfile)
        if (idx >= 0) {
            suppressSpinnerCallback = true
            profileSpinner.setSelection(idx)
            suppressSpinnerCallback = false
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinnerCallback) return
                val selected = adapter.getItem(position) ?: return
                if (selected != currentProfile) {
                    currentProfile = selected
                    prefs().edit().putString(KEY_CURRENT_PROFILE, currentProfile).apply()
                    refreshScreenForCurrentProfile()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun promptNewProfile() {
        val input = EditText(this)
        input.hint = "مثال: بيت العائلة، العمل..."
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(this)
            .setTitle("اسم العنوان الجديد")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "اكتب اسم للعنوان", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val profiles = getProfiles()
                if (profiles.contains(name)) {
                    Toast.makeText(this, "الاسم موجود مسبقاً", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                profiles.add(name)
                saveProfiles(profiles)
                currentProfile = name
                prefs().edit().putString(KEY_CURRENT_PROFILE, currentProfile).apply()
                setupProfileSpinner(profiles)
                showSetupScreen()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmDeleteProfile() {
        val profiles = getProfiles()
        if (profiles.size <= 1) {
            Toast.makeText(this, "لازم يبقى عنوان واحد على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف \"$currentProfile\"؟")
            .setMessage("راح يتم حذف كل بياناته وصوره نهائياً.")
            .setPositiveButton("حذف") { _, _ ->
                profilePhotoFiles(currentProfile).forEach { it.delete() }
                profilePhotosDir(currentProfile).delete()
                prefs().edit()
                    .remove(keyFor("maps_link", currentProfile))
                    .remove(keyFor("national_address", currentProfile))
                    .remove(keyFor("building_number", currentProfile))
                    .remove(keyFor("drop_off", currentProfile))
                    .apply()

                profiles.remove(currentProfile)
                saveProfiles(profiles)
                currentProfile = profiles.first()
                prefs().edit().putString(KEY_CURRENT_PROFILE, currentProfile).apply()
                setupProfileSpinner(profiles)
                refreshScreenForCurrentProfile()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- عرض الشاشات ----------

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

    private fun refreshScreenForCurrentProfile() {
        val hasSavedData = !prefs().getString(keyFor("maps_link", currentProfile), "").isNullOrEmpty() ||
                !prefs().getString(keyFor("national_address", currentProfile), "").isNullOrEmpty()

        if (hasSavedData) showSendScreen() else showSetupScreen()
    }

    private fun showSetupScreen() {
        setupLayout.visibility = LinearLayout.VISIBLE
        sendLayout.visibility = LinearLayout.GONE

        mapsLinkInput.setText(prefs().getString(keyFor("maps_link", currentProfile), ""))
        nationalAddressInput.setText(prefs().getString(keyFor("national_address", currentProfile), ""))
        buildingNumberInput.setText(prefs().getString(keyFor("building_number", currentProfile), ""))
        dropOffInput.setText(prefs().getString(keyFor("drop_off", currentProfile), ""))
        renderThumbs(photoThumbsRow, profilePhotoFiles(currentProfile))
        setupStatus.text = ""
    }

    private fun showSendScreen() {
        setupLayout.visibility = LinearLayout.GONE
        sendLayout.visibility = LinearLayout.VISIBLE

        savedDetailsView.text = buildMessageText()
        renderThumbs(savedPhotoThumbsRow, profilePhotoFiles(currentProfile))
        sendStatus.text = ""
    }

    private fun buildMessageText(): String {
        val mapsLink = prefs().getString(keyFor("maps_link", currentProfile), "") ?: ""
        val nationalAddress = prefs().getString(keyFor("national_address", currentProfile), "") ?: ""
        val buildingNumber = prefs().getString(keyFor("building_number", currentProfile), "") ?: ""
        val dropOff = prefs().getString(keyFor("drop_off", currentProfile), "") ?: ""

        val sb = StringBuilder()
        if (mapsLink.isNotEmpty()) sb.append("📍 الموقع: $mapsLink\n")
        if (nationalAddress.isNotEmpty()) sb.append("🏷️ العنوان الوطني: $nationalAddress\n")
        if (buildingNumber.isNotEmpty()) sb.append("🏢 رقم المبنى: $buildingNumber\n")
        if (dropOff.isNotEmpty()) sb.append("📦 مكان وضع الشحنة: $dropOff")
        return sb.toString().trim()
    }

    // ---------- الإرسال ----------

    private fun sendLocation() {
        val text = buildMessageText()
        val files = profilePhotoFiles(currentProfile)

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
