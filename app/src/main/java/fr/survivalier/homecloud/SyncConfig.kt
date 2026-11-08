package fr.survivalier.homecloud

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Types de fichiers que HomeCloud Sync peut envoyer ; [folder] = sous-dossier créé sur le cloud. */
enum class SyncCategory(val label: String, val folder: String, val hint: String, val exts: Set<String>) {
    PHOTOS(
        "Photos", "Photos", "JPG, PNG, HEIC, RAW…",
        setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "arw", "cr2", "nef", "tif", "tiff", "avif"),
    ),
    VIDEOS(
        "Vidéos", "Vidéos", "MP4, MKV, MOV, 3GP…",
        setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "wmv"),
    ),
    AUDIO(
        "Audio", "Audio", "MP3, FLAC, M4A, enregistrements…",
        setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr", "mid"),
    ),
    DOCUMENTS(
        "Documents", "Documents", "PDF, Word, Excel, PowerPoint, ODF, TXT…",
        setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "odg", "odb",
            "txt", "md", "rtf", "csv", "epub", "json", "xml", "html", "htm",
        ),
    ),
    ARCHIVES("Archives", "Archives", "ZIP, RAR, 7Z, TAR, GZ…", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")),
    OTHER("Autres types", "Autres", "", emptySet()),
    ;

    val isMedia: Boolean get() = this == PHOTOS || this == VIDEOS || this == AUDIO
}

private val categoryByExt: Map<String, SyncCategory> =
    SyncCategory.entries.flatMap { c -> c.exts.map { it to c } }.toMap()

fun categoryOf(ext: String): SyncCategory? = categoryByExt[ext.lowercase()]

/** Dossier de l'appareil choisi via le sélecteur système (accès persistant). */
data class SyncFolder(val uri: String, val label: String)

/** Réglages de HomeCloud Sync (préférences privées). Le mot de passe est chiffré avec le Keystore Android. */
class SyncConfig(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("homecloud_sync", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) { p.edit().putBoolean("enabled", v).apply() }

    // ---- compte utilisé en arrière-plan ----
    var address: String
        get() = p.getString("address", "") ?: ""
        set(v) { p.edit().putString("address", v).apply() }

    var username: String
        get() = p.getString("username", "") ?: ""
        set(v) { p.edit().putString("username", v).apply() }

    var serverName: String
        get() = p.getString("server_name", "") ?: ""
        set(v) { p.edit().putString("server_name", v).apply() }

    fun setPassword(pw: String?) {
        p.edit().apply { if (pw == null) remove("pass") else putString("pass", SecretStore.encrypt(pw)) }.apply()
    }

    fun password(): String? = p.getString("pass", null)?.let { SecretStore.decrypt(it) }

    // ---- destination ----
    var diskId: String
        get() = p.getString("disk_id", "") ?: ""
        set(v) { p.edit().putString("disk_id", v).apply() }

    var diskName: String
        get() = p.getString("disk_name", "") ?: ""
        set(v) { p.edit().putString("disk_name", v).apply() }

    var remoteBase: String
        get() = p.getString("remote_base", null) ?: defaultBase()
        set(v) { p.edit().putString("remote_base", normalizeBase(v)).apply() }

    // ---- contenu ----
    var categories: Set<SyncCategory>
        get() = p.getStringSet("categories", null)
            ?.mapNotNull { n -> SyncCategory.entries.firstOrNull { it.name == n } }?.toSet()
            ?: setOf(SyncCategory.PHOTOS, SyncCategory.VIDEOS)
        set(v) { p.edit().putStringSet("categories", v.map { it.name }.toHashSet()).apply() }

    /** Extensions supplémentaires saisies par l'utilisateur (« * » = tous les fichiers des dossiers choisis). */
    var customExts: String
        get() = p.getString("custom_exts", "") ?: ""
        set(v) { p.edit().putString("custom_exts", v).apply() }

    val customExtSet: Set<String>
        get() = customExts.split(',', ' ', ';', '\n').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Inclure la galerie de l'appareil (photos / vidéos / audio) sans choisir de dossier. */
    var useMediaStore: Boolean
        get() = p.getBoolean("use_mediastore", true)
        set(v) { p.edit().putBoolean("use_mediastore", v).apply() }

    var folders: List<SyncFolder>
        get() {
            val a = runCatching { JSONArray(p.getString("folders", "[]")) }.getOrDefault(JSONArray())
            return (0 until a.length()).map { a.getJSONObject(it).let { o -> SyncFolder(o.getString("uri"), o.optString("label")) } }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("uri", it.uri).put("label", it.label)) }
            p.edit().putString("folders", a.toString()).apply()
        }

    // ---- planification ----
    var intervalMin: Int
        get() = p.getInt("interval", 60).coerceAtLeast(15)
        set(v) { p.edit().putInt("interval", v.coerceAtLeast(15)).apply() }

    var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", true)
        set(v) { p.edit().putBoolean("wifi_only", v).apply() }

    var chargingOnly: Boolean
        get() = p.getBoolean("charging_only", false)
        set(v) { p.edit().putBoolean("charging_only", v).apply() }

    /** Si l'adresse enregistrée ne répond plus, chercher le serveur du même nom sur le réseau local. */
    var autoDiscover: Boolean
        get() = p.getBoolean("auto_discover", false)
        set(v) { p.edit().putBoolean("auto_discover", v).apply() }

    // ---- dernier passage ----
    var lastRunAt: Long
        get() = p.getLong("last_run_at", 0L)
        set(v) { p.edit().putLong("last_run_at", v).apply() }

    var lastSummary: String
        get() = p.getString("last_summary", "") ?: ""
        set(v) { p.edit().putString("last_summary", v).apply() }

    /** Catégorie de destination d'un fichier d'extension [ext], ou null s'il n'est pas à synchroniser. */
    fun match(ext: String): SyncCategory? {
        val known = categoryOf(ext)
        if (known != null && known in categories) return known
        val custom = customExtSet
        if ("*" in custom || ext.lowercase() in custom) return known ?: SyncCategory.OTHER
        return null
    }

    fun clearCredentials() {
        p.edit().remove("pass").apply()
    }

    companion object {
        fun defaultBase(): String = "/HomeCloud Sync/" + safeFileName(Build.MODEL ?: "Android")

        /** Vrai si [path] (dossier du disque) est le dossier de synchronisation ou son dossier racine (« /HomeCloud Sync »). */
        fun isSyncFolder(path: String, base: String): Boolean {
            val b = normalizeBase(base)
            val root = "/" + b.trim('/').substringBefore('/')
            return path == b || path == root
        }

        fun normalizeBase(s: String): String {
            val segs = s.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.map { safeFileName(it) }
            return "/" + segs.joinToString("/")
        }
    }
}

/** Chiffrement AES-GCM avec une clé non exportable du Keystore Android. */
object SecretStore {
    private const val ALIAS = "homecloud_sync_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }.getOrNull()
}
