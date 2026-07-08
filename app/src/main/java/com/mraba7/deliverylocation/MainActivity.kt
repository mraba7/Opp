package com.mraba7.deliverylocation

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    private val PREFS_NAME = "delivery_location_prefs"
    private val KEY_PROFILES = "profiles_list"
    private val KEY_CURRENT_PROFILE = "current_profile"
    private val KEY_SEND_LOG = "send_log"
    private val MAX_PHOTOS = 6
    private val MAX_LOG_ENTRIES = 5
    private val DEFAULT_PROFILE = "المنزل"
    private val MAP_ZOOM = 17

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var profileSpinner: Spinner
    private lateinit var addProfileBtn: Button
    private lateinit var deleteProfileBtn: Button
    private lateinit var backupExportBtn: Button
    private lateinit var backupImportBtn: Button
    private lateinit var historyBtn: Button

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

    private lateinit var staticMapView: ImageView
    private lateinit var savedPhotoThumbsRow: LinearLayout
    private lateinit var cardModeSwitch: Switch
    private lateinit var savedDetailsView: TextView
    private lateinit var sendBtn: Button
    private lateinit var waTextBtn: Button
    private lateinit var sendStatus: TextView
    private lateinit var editLink: TextView

    private var currentProfile: String = DEFAULT_PROFILE
    private var suppressSpinnerCallback = false
    private var currentMapBitmap: Bitmap? = null

    // ---------- تخزين عام ----------

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

    private fun mapCacheFile(profile: String): File {
        val dir = File(filesDir, "maps")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${profile}_map.png")
    }

    // ---------- اختيار الصور ----------

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

    // ---------- نسخ احتياطي JSON ----------

    private val backupExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = buildBackupJson()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toString().toByteArray())
                }
                Toast.makeText(this, "تم حفظ النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر حفظ النسخة الاحتياطية", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val backupImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8) ?: ""
                confirmRestoreBackup(text)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر قراءة الملف", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildBackupJson(): JSONObject {
        val root = JSONObject()
        val profilesArray = JSONArray()
        for (name in getProfiles()) {
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("maps_link", prefs().getString(keyFor("maps_link", name), "") ?: "")
            obj.put("national_address", prefs().getString(keyFor("national_address", name), "") ?: "")
            obj.put("building_number", prefs().getString(keyFor("building_number", name), "") ?: "")
            obj.put("drop_off", prefs().getString(keyFor("drop_off", name), "") ?: "")

            val photosArray = JSONArray()
            for (file in profilePhotoFiles(name)) {
                try {
                    val bytes = file.readBytes()
                    photosArray.put(Base64.encodeToString(bytes, Base64.NO_WRAP))
                } catch (e: Exception) {
                    // تجاهل صورة تالفة
                }
            }
            obj.put("photos", photosArray)
            profilesArray.put(obj)
        }
        root.put("profiles", profilesArray)
        root.put("current_profile", currentProfile)
        return root
    }

    private fun confirmRestoreBackup(jsonText: String) {
        AlertDialog.Builder(this)
            .setTitle("استيراد نسخة احتياطية")
            .setMessage("هذا راح يستبدل كل العناوين والصور الحالية بالنسخة المستوردة. متأكد؟")
            .setPositiveButton("استيراد") { _, _ -> restoreBackup(jsonText) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun restoreBackup(jsonText: String) {
        try {
            val root = JSONObject(jsonText)
            val profilesArray = root.getJSONArray("profiles")

            // مسح البيانات الحالية بالكامل
            for (name in getProfiles()) {
                profilePhotoFiles(name).forEach { it.delete() }
                profilePhotosDir(name).delete()
                prefs().edit()
                    .remove(keyFor("maps_link", name))
                    .remove(keyFor("national_address", name))
                    .remove(keyFor("building_number", name))
                    .remove(keyFor("drop_off", name))
                    .apply()
            }

            val newProfiles = mutableListOf<String>()
            for (i in 0 until profilesArray.length()) {
                val obj = profilesArray.getJSONObject(i)
                val name = obj.getString("name")
                newProfiles.add(name)

                prefs().edit()
                    .putString(keyFor("maps_link", name), obj.optString("maps_link", ""))
                    .putString(keyFor("national_address", name), obj.optString("national_address", ""))
                    .putString(keyFor("building_number", name), obj.optString("building_number", ""))
                    .putString(keyFor("drop_off", name), obj.optString("drop_off", ""))
                    .apply()

                val photosArray = obj.optJSONArray("photos")
                if (photosArray != null) {
                    for (p in 0 until photosArray.length()) {
                        try {
                            val bytes = Base64.decode(photosArray.getString(p), Base64.NO_WRAP)
                            val target = File(profilePhotosDir(name), "photo_$p.jpg")
                            FileOutputStream(target).use { it.write(bytes) }
                        } catch (e: Exception) {
                            // تجاهل
                        }
                    }
                }
            }

            saveProfiles(newProfiles)
            currentProfile = root.optString("current_profile", newProfiles.firstOrNull() ?: DEFAULT_PROFILE)
            if (!newProfiles.contains(currentProfile)) currentProfile = newProfiles.firstOrNull() ?: DEFAULT_PROFILE
            prefs().edit().putString(KEY_CURRENT_PROFILE, currentProfile).apply()

            setupProfileSpinner(newProfiles)
            refreshScreenForCurrentProfile()
            Toast.makeText(this, "تم الاستيراد بنجاح", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ملف النسخة الاحتياطية غير صالح", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- سجل الإرسال ----------

    private fun getSendLog(): List<String> {
        val raw = prefs().getString(KEY_SEND_LOG, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("###").filter { it.isNotEmpty() }
    }

    private fun logSend(profile: String) {
        val entries = getSendLog().toMutableList()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("ar")).format(Date())
        entries.add(0, "$now|||$profile")
        while (entries.size > MAX_LOG_ENTRIES) entries.removeAt(entries.size - 1)
        prefs().edit().putString(KEY_SEND_LOG, entries.joinToString("###")).apply()
    }

    private fun showHistoryDialog() {
        val entries = getSendLog()
        val message = if (entries.isEmpty()) {
            "لا يوجد سجل إرسال بعد"
        } else {
            entries.joinToString("\n") { entry ->
                val parts = entry.split("|||")
                if (parts.size == 2) "🕓 ${parts[0]} — ${parts[1]}" else entry
            }
        }
        AlertDialog.Builder(this)
            .setTitle("آخر عمليات الإرسال")
            .setMessage(message)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    // ---------- الخريطة المصغّرة (OpenStreetMap) ----------

    private fun parseCoordsFromUrl(url: String): Pair<Double, Double>? {
        val patterns = listOf(
            Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
            Regex("place/(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
            Regex("q=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) {
                val lat = m.groupValues[1].toDoubleOrNull()
                val lon = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) return Pair(lat, lon)
            }
        }
        return null
    }

    private fun resolveCoordinates(originalLink: String): Pair<Double, Double>? {
        parseCoordsFromUrl(originalLink)?.let { return it }

        var url = originalLink
        repeat(5) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DeliveryLocationApp/1.0")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    parseCoordsFromUrl(loc)?.let { return it }
                    url = loc
                } else {
                    return parseCoordsFromUrl(url)
                }
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun fetchMapBitmap(lat: Double, lon: Double): Bitmap {
        val n = 1 shl MAP_ZOOM
        val xTile = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val yTile = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n

        val tileX = floor(xTile).toInt()
        val tileY = floor(yTile).toInt()
        val px = ((xTile - tileX) * 256).toInt()
        val py = ((yTile - tileY) * 256).toInt()

        // نمط خرائط أحدث وأوضح ألوان (CARTO Voyager) بدل الطراز الكلاسيكي القديم لـ OSM
        val subdomain = listOf("a", "b", "c", "d").random()
        val tileUrl = "https://$subdomain.basemaps.cartocdn.com/rastertiles/voyager/$MAP_ZOOM/$tileX/$tileY.png"
        val conn = URL(tileUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "DeliveryLocationApp/1.0 (personal use)")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val bytes = conn.inputStream.use { it.readBytes() }

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val bmp = decoded.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)

        drawPinMarker(canvas, px.toFloat(), py.toFloat())

        // إسناد المصدر (مطلوب من سياسة الاستخدام)
        val attrBg = Paint(Paint.ANTI_ALIAS_FLAG)
        attrBg.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, bmp.height - 20f, 190f, bmp.height.toFloat(), attrBg)
        val attrText = Paint(Paint.ANTI_ALIAS_FLAG)
        attrText.color = Color.WHITE
        attrText.textSize = 10f
        canvas.drawText("© OpenStreetMap, © CARTO", 6f, bmp.height - 6f, attrText)

        return bmp
    }

    private fun drawPinMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = 13f

        // ظل خفيف تحت الدبوس
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        shadowPaint.color = Color.argb(60, 0, 0, 0)
        canvas.drawOval(cx - r * 0.7f, cy + r * 0.9f, cx + r * 0.7f, cy + r * 1.3f, shadowPaint)

        // جسم الدبوس (شكل دمعة)
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        pinPaint.color = Color.parseColor("#128C4A")
        val path = android.graphics.Path()
        path.addCircle(cx, cy - r, r, android.graphics.Path.Direction.CW)
        path.moveTo(cx - r * 0.65f, cy - r * 0.25f)
        path.lineTo(cx, cy + r * 1.1f)
        path.lineTo(cx + r * 0.65f, cy - r * 0.25f)
        path.close()
        canvas.drawPath(path, pinPaint)

        // حدّ أبيض حول الدبوس
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        outlinePaint.color = Color.WHITE
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = 2.5f
        canvas.drawCircle(cx, cy - r, r, outlinePaint)

        // نقطة بيضاء بالمنتصف
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy - r, r * 0.4f, dotPaint)
    }

    private fun loadStaticMap(profile: String) {
        val link = prefs().getString(keyFor("maps_link", profile), "") ?: ""
        if (link.isEmpty()) {
            currentMapBitmap = null
            staticMapView.visibility = ImageView.GONE
            return
        }

        val cacheFile = mapCacheFile(profile)
        val linkHashKey = keyFor("maps_link_hash", profile)
        val savedHash = prefs().getString(linkHashKey, "")
        val newHash = link.hashCode().toString()

        if (cacheFile.exists() && savedHash == newHash) {
            try {
                val bmp = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bmp != null) {
                    currentMapBitmap = bmp
                    staticMapView.setImageBitmap(bmp)
                    staticMapView.visibility = ImageView.VISIBLE
                    return
                }
            } catch (e: Exception) {
                // نكمل ونجيبها من الإنترنت
            }
        }

        staticMapView.visibility = ImageView.GONE
        executor.execute {
            try {
                val coords = resolveCoordinates(link)
                if (coords != null) {
                    val bmp = fetchMapBitmap(coords.first, coords.second)
                    try {
                        FileOutputStream(cacheFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        prefs().edit().putString(linkHashKey, newHash).apply()
                    } catch (e: Exception) {
                        // تجاهل فشل الكاش
                    }
                    runOnUiThread {
                        if (currentProfile == profile) {
                            currentMapBitmap = bmp
                            staticMapView.setImageBitmap(bmp)
                            staticMapView.visibility = ImageView.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                // فشل تحميل الخريطة، نتجاهل بصمت
            }
        }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options()
            boundsOptions.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            var inSampleSize = 1
            val (rawWidth, rawHeight) = boundsOptions.outWidth to boundsOptions.outHeight
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val options = BitmapFactory.Options()
            options.inSampleSize = inSampleSize
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- بطاقة العنوان المدمجة ----------

    private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, radius: Float) {
        val path = android.graphics.Path()
        path.addRoundRect(dst, radius, radius, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, null, dst, null)
        canvas.restore()

        val border = Paint(Paint.ANTI_ALIAS_FLAG)
        border.color = Color.parseColor("#E0E0E0")
        border.style = Paint.Style.STROKE
        border.strokeWidth = 2f
        canvas.drawRoundRect(dst, radius, radius, border)
    }

    private fun buildCardBitmap(profile: String): Bitmap {
        val width = 900
        val padding = 30
        val text = buildMessageText().ifEmpty { " " }
        val photos = profilePhotoFiles(profile)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.parseColor("#1F3B33")
        textPaint.textSize = 30f

        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width - padding * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.25f, 1.25f)
            .build()

        val headerHeight = 120
        val mapHeight = if (currentMapBitmap != null) 260 else 0
        val dividerGap = 22
        val photosRows = if (photos.isEmpty()) 0 else ((photos.size + 1) / 2)
        val photoCellHeight = 260
        val photosHeight = if (photosRows > 0) photosRows * (photoCellHeight + 16) else 0
        val footerHeight = 50

        val totalHeight = (headerHeight + padding +
                (if (mapHeight > 0) mapHeight + dividerGap else 0) +
                layout.height + dividerGap +
                photosHeight +
                footerHeight + padding).coerceAtLeast(300)

        val bmp = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        // ===== شريط الهيدر الملون =====
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        headerPaint.shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            Color.parseColor("#25D366"), Color.parseColor("#128C4A"),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), headerPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        titlePaint.color = Color.WHITE
        titlePaint.textSize = 46f
        titlePaint.isFakeBoldText = true
        canvas.drawText("📍 $profile", padding.toFloat(), 68f, titlePaint)

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        subtitlePaint.color = Color.argb(230, 255, 255, 255)
        subtitlePaint.textSize = 24f
        canvas.drawText("تفاصيل التوصيل", padding.toFloat(), 100f, subtitlePaint)

        var cursorY = headerHeight + padding

        // ===== الخريطة (بحواف دائرية) =====
        if (currentMapBitmap != null) {
            val dst = RectF(padding.toFloat(), cursorY.toFloat(), (width - padding).toFloat(), (cursorY + mapHeight).toFloat())
            drawRoundedBitmap(canvas, currentMapBitmap!!, dst, 20f)
            cursorY += mapHeight + dividerGap
        }

        // ===== التفاصيل النصية =====
        canvas.save()
        canvas.translate(padding.toFloat(), cursorY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        cursorY += layout.height + dividerGap

        // خط فاصل خفيف
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        dividerPaint.color = Color.parseColor("#E8E8E8")
        dividerPaint.strokeWidth = 2f
        if (photos.isNotEmpty()) {
            canvas.drawLine(padding.toFloat(), (cursorY - dividerGap / 2).toFloat(), (width - padding).toFloat(), (cursorY - dividerGap / 2).toFloat(), dividerPaint)
        }

        // ===== شبكة الصور (بحواف دائرية) =====
        if (photos.isNotEmpty()) {
            var col = 0
            var rowY = cursorY
            val cellWidth = (width - padding * 2 - 16) / 2
            for (file in photos) {
                try {
                    val photoBmp = decodeSampledBitmap(file, cellWidth, photoCellHeight)
                    if (photoBmp != null) {
                        val x = padding + col * (cellWidth + 16)
                        val dst = RectF(x.toFloat(), rowY.toFloat(), (x + cellWidth).toFloat(), (rowY + photoCellHeight).toFloat())
                        drawRoundedBitmap(canvas, photoBmp, dst, 16f)
                    }
                } catch (e: Exception) {
                    // تجاهل صورة تالفة
                }
                col++
                if (col == 2) {
                    col = 0
                    rowY += photoCellHeight + 16
                }
            }
            cursorY = rowY + (if (col > 0) photoCellHeight + 16 else 0)
        }

        // ===== تذييل بسيط =====
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        footerPaint.color = Color.parseColor("#9AA9A2")
        footerPaint.textSize = 20f
        footerPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("🚚 عبر تطبيق موقعي للمندوب", width / 2f, (totalHeight - padding / 2).toFloat(), footerPaint)

        return bmp
    }

    // ---------- صوت التأكيد ----------

    private fun playConfirmSound() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
        } catch (e: Exception) {
            // تجاهل لو الجهاز ما يدعمها
        }
    }

    // ---------- دورة حياة الشاشة ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        profileSpinner = findViewById(R.id.profileSpinner)
        addProfileBtn = findViewById(R.id.addProfileBtn)
        deleteProfileBtn = findViewById(R.id.deleteProfileBtn)
        backupExportBtn = findViewById(R.id.backupExportBtn)
        backupImportBtn = findViewById(R.id.backupImportBtn)
        historyBtn = findViewById(R.id.historyBtn)

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

        staticMapView = findViewById(R.id.staticMapView)
        savedPhotoThumbsRow = findViewById(R.id.savedPhotoThumbsRow)
        cardModeSwitch = findViewById(R.id.cardModeSwitch)
        savedDetailsView = findViewById(R.id.savedDetailsView)
        sendBtn = findViewById(R.id.sendBtn)
        waTextBtn = findViewById(R.id.waTextBtn)
        sendStatus = findViewById(R.id.sendStatus)
        editLink = findViewById(R.id.editLink)

        var profiles = getProfiles()
        if (profiles.isEmpty()) {
            profiles = mutableListOf(DEFAULT_PROFILE)
            saveProfiles(profiles)
            prefs().edit().putString(KEY_CURRENT_PROFILE, DEFAULT_PROFILE).apply()
        }
        currentProfile = prefs().getString(KEY_CURRENT_PROFILE, profiles.first()) ?: profiles.first()
        if (!profiles.contains(currentProfile)) currentProfile = profiles.first()

        setupProfileSpinner(profiles)

        choosePhotoBtn.setOnClickListener { pickImagesLauncher.launch("image/*") }

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

        backupExportBtn.setOnClickListener {
            backupExportLauncher.launch("delivery-locations-backup.json")
        }
        backupImportBtn.setOnClickListener {
            backupImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        historyBtn.setOnClickListener { showHistoryDialog() }

        sendBtn.setOnClickListener { sendLocation() }
        waTextBtn.setOnClickListener { sendTextOnly() }

        refreshScreenForCurrentProfile()
    }

    private fun setupProfileSpinner(profiles: List<String>) {
        val adapter = ProfileAdapter(this, profiles)
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
                if (position < 0 || position >= profiles.size) return
                val selected = profiles[position]
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
                mapCacheFile(currentProfile).delete()
                prefs().edit()
                    .remove(keyFor("maps_link", currentProfile))
                    .remove(keyFor("national_address", currentProfile))
                    .remove(keyFor("building_number", currentProfile))
                    .remove(keyFor("drop_off", currentProfile))
                    .remove(keyFor("maps_link_hash", currentProfile))
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
        loadStaticMap(currentProfile)
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
        val useCard = cardModeSwitch.isChecked

        val intent: Intent

        if (useCard) {
            try {
                val cardBmp = buildCardBitmap(currentProfile)
                val cardFile = File(filesDir, "card_share.jpg")
                FileOutputStream(cardFile).use { cardBmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", cardFile)

                intent = Intent(Intent.ACTION_SEND)
                intent.type = "image/jpeg"
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.putExtra(Intent.EXTRA_TEXT, text)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                sendStatus.text = "تعذر إنشاء البطاقة: ${e.javaClass.simpleName} - ${e.message}"
                return
            }
        } else if (files.isEmpty()) {
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
            logSend(currentProfile)
            playConfirmSound()
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                startActivity(Intent.createChooser(intent, "إرسال عبر"))
                logSend(currentProfile)
                playConfirmSound()
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
        logSend(currentProfile)
        playConfirmSound()
        sendStatus.text = "تم فتح واتساب، أرفق الصور يدوياً إذا لزم"
    }
}
