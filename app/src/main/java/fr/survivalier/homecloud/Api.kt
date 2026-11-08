package fr.survivalier.homecloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Client de l'API HTTP/JSON de Home Cloud (voir README du serveur, section « API »). */
class Api {
    @Volatile var server: Server? = null
    @Volatile var token: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------- plomberie ----------

    private fun url(endpoint: String, q: Map<String, String> = emptyMap()): HttpUrl {
        val s = server ?: throw ApiException("Aucun serveur sélectionné")
        return HttpUrl.Builder()
            .scheme(if (s.https) "https" else "http")
            .host(s.host)
            .port(s.port)
            .addPathSegments(endpoint.trimStart('/'))
            .apply { q.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    private fun req(endpoint: String, q: Map<String, String> = emptyMap()): Request.Builder =
        Request.Builder().url(url(endpoint, q)).apply {
            if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        }

    private fun form(p: Map<String, String>): FormBody =
        FormBody.Builder().apply { p.forEach { (k, v) -> add(k, v) } }.build()

    private fun fail(code: Int, body: String): Nothing {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val msg = o?.optString("error")?.takeIf { it.isNotBlank() } ?: "Erreur HTTP $code"
        throw ApiException(msg, code, code == 423 || o?.optBoolean("locked") == true)
    }

    private suspend fun call(b: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(body) }.getOrNull()
            if (o != null && o.optBoolean("ok")) o else fail(r.code, body)
        }
    }

    private suspend fun get(endpoint: String, q: Map<String, String> = emptyMap()) = call(req(endpoint, q).get())

    private suspend fun post(endpoint: String, p: Map<String, String> = emptyMap()) =
        call(req(endpoint).post(form(p)))

    private fun lockQ(base: MutableMap<String, String>, pass: String): Map<String, String> {
        if (pass.isNotEmpty()) base["lockpass"] = pass
        return base
    }

    // ---------- compte ----------

    suspend fun login(user: String, pass: String): Pair<String, User> {
        val o = post("/api/login", mapOf("user" to user, "pass" to pass))
        return o.getString("token") to parseUser(o.getJSONObject("user"))
    }

    suspend fun me(): User = parseUser(get("/api/me").getJSONObject("user"))

    suspend fun logout() { post("/api/logout") }

    suspend fun password(old: String, new: String) { post("/api/password", mapOf("old" to old, "new" to new)) }

    suspend fun avatar(user: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(req("/api/avatar", mapOf("user" to user)).get().build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }

    suspend fun setAvatar(jpeg: ByteArray) {
        call(req("/api/avatar").post(jpeg.toRequestBody("image/jpeg".toMediaType())))
    }

    private fun parseUser(o: JSONObject) = User(o.optString("name"), o.optBoolean("admin"), o.optString("personal"))

    // ---------- disques & fichiers ----------

    suspend fun disks(): List<Disk> {
        val a = get("/api/disks").getJSONArray("disks")
        return (0 until a.length()).map {
            val d = a.getJSONObject(it)
            Disk(
                d.getString("id"), d.optString("name"), d.optLong("size"), d.optLong("used"),
                d.optBoolean("personal"), d.optString("owner"), d.optString("perm") == "rw",
            )
        }
    }

    suspend fun list(disk: String, path: String): List<Entry> {
        val a = get("/api/list", mapOf("disk" to disk, "path" to path)).getJSONArray("entries")
        return (0 until a.length()).map {
            val e = a.getJSONObject(it)
            Entry(e.getString("name"), e.optBoolean("dir"), e.optLong("size"), e.optLong("mtime"), e.optBoolean("locked"))
        }
    }

    suspend fun info(disk: String, path: String): FileInfo {
        val o = get("/api/info", mapOf("disk" to disk, "path" to path))
        return FileInfo(o.optLong("ctime"), if (o.optBoolean("locked")) o.optString("lock_owner") else null)
    }

    suspend fun mkdir(disk: String, path: String) { post("/api/mkdir", mapOf("disk" to disk, "path" to path)) }

    suspend fun delete(disk: String, path: String, lockPass: String = "") {
        post("/api/delete", lockQ(mutableMapOf("disk" to disk, "path" to path), lockPass))
    }

    suspend fun rename(disk: String, from: String, to: String, lockPass: String = "") {
        post("/api/rename", lockQ(mutableMapOf("disk" to disk, "path" to from, "to" to to), lockPass))
    }

    suspend fun lock(disk: String, path: String, pass: String) {
        post("/api/lock", mapOf("disk" to disk, "path" to path, "pass" to pass))
    }

    suspend fun unlock(disk: String, path: String, pass: String) {
        post("/api/unlock", mapOf("disk" to disk, "path" to path, "pass" to pass))
    }

    suspend fun lockCheck(disk: String, path: String, pass: String) {
        post("/api/lock_check", mapOf("disk" to disk, "path" to path, "lockpass" to pass))
    }

    /** Télécharge un fichier vers [sink] (fermé par l'appelant). */
    suspend fun download(
        disk: String, path: String, lockPass: String, sink: OutputStream, onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val q = lockQ(mutableMapOf("disk" to disk, "path" to path), lockPass)
        http.newCall(req("/api/download", q).get().build()).execute().use { r ->
            if (!r.isSuccessful) fail(r.code, r.body?.string().orEmpty())
            val body = r.body ?: fail(r.code, "")
            val total = body.contentLength()
            val buf = ByteArray(65536)
            var done = 0L
            body.byteStream().use { input ->
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            sink.flush()
        }
    }

    /** Envoie (ou remplace) un fichier ; [open] est rappelée si OkHttp doit réémettre la requête. */
    suspend fun upload(
        disk: String, path: String, lockPass: String, length: Long, open: () -> InputStream, onProgress: (sent: Long) -> Unit,
    ) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(65536)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        val q = lockQ(mutableMapOf("disk" to disk, "path" to path), lockPass)
        call(req("/api/upload", q).post(body))
    }

    // ---------- administration ----------

    suspend fun adminUsers(): List<AdminUser> {
        val a = get("/api/admin/users").getJSONArray("users")
        return (0 until a.length()).map {
            val u = a.getJSONObject(it)
            AdminUser(u.optString("name"), u.optBoolean("admin"), u.optString("personal"), u.optLong("quota"))
        }
    }

    suspend fun createUser(name: String, pass: String, quotaGb: Int, admin: Boolean) {
        post("/api/admin/user_create", mapOf("name" to name, "pass" to pass, "quota_gb" to quotaGb.toString(), "admin" to if (admin) "1" else "0"))
    }

    suspend fun deleteUser(name: String) { post("/api/admin/user_delete", mapOf("name" to name)) }

    suspend fun createDisk(name: String, sizeGb: Int) {
        post("/api/admin/disk_create", mapOf("name" to name, "size_gb" to sizeGb.toString()))
    }

    suspend fun deleteDisk(id: String) { post("/api/admin/disk_delete", mapOf("disk" to id)) }

    suspend fun perms(disk: String): List<Perm> {
        val a = get("/api/admin/perms", mapOf("disk" to disk)).getJSONArray("perms")
        return (0 until a.length()).map {
            val p = a.getJSONObject(it)
            Perm(p.optString("user"), p.optString("perm") == "rw")
        }
    }

    suspend fun setPerm(disk: String, user: String, perm: String) {
        post("/api/admin/perm_set", mapOf("disk" to disk, "user" to user, "perm" to perm))
    }
}
