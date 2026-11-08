package fr.survivalier.homecloud

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
//  État partagé avec l'interface
// =====================================================================

class SyncProgress(val text: String, val done: Int, val total: Int, val fraction: Float)

object SyncStatus {
    /** Non nul pendant qu'une synchronisation tourne. */
    val progress = MutableStateFlow<SyncProgress?>(null)

    /** Incrémenté à chaque ligne de journal (pour rafraîchir l'écran). */
    val logRev = MutableStateFlow(0)

    /** (date en ms, résumé) du dernier passage. */
    val lastRun = MutableStateFlow<Pair<Long, String>?>(null)
}

/** Journal texte (250 dernières lignes) dans le stockage privé de l'application. */
object SyncLog {
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "sync_log.txt")

    @Synchronized
    fun add(ctx: Context, line: String) {
        runCatching {
            val f = file(ctx)
            val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
            f.appendText("$ts  $line\n")
            if (f.length() > 64_000) f.writeText(f.readLines().takeLast(250).joinToString("\n") + "\n")
        }
        SyncStatus.logRev.value = SyncStatus.logRev.value + 1
    }

    fun tail(ctx: Context, n: Int): List<String> =
        runCatching { file(ctx).readLines().takeLast(n) }.getOrDefault(emptyList())
}

/** Mémoire de synchro : « clé du fichier source » → « taille:date » du fichier tel qu'il a été envoyé. */
class SyncState(ctx: Context, private val signature: String) {
    private val file = File(ctx.applicationContext.filesDir, "sync_state.json")
    val map = HashMap<String, String>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            if (o.optString("sig") == signature) {
                val m = o.getJSONObject("m")
                for (k in m.keys()) map[k] = m.getString(k)
            }
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("sig", signature).put("m", JSONObject(map.toMap())).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        fun reset(ctx: Context) { File(ctx.applicationContext.filesDir, "sync_state.json").delete() }
    }
}

// =====================================================================
//  Autorisations
// =====================================================================

object SyncPerms {
    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun canRead(ctx: Context, cat: SyncCategory): Boolean {
        if (!cat.isMedia) return true
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = when (cat) {
                SyncCategory.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
                SyncCategory.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                else -> Manifest.permission.READ_MEDIA_AUDIO
            }
            return granted(ctx, perm) ||
                (Build.VERSION.SDK_INT >= 34 && cat != SyncCategory.AUDIO && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
        return granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Catégories de la galerie activées mais dont l'accès n'est pas (ou plus) accordé. */
    fun missing(ctx: Context, cfg: SyncConfig): List<SyncCategory> =
        if (!cfg.useMediaStore) emptyList() else cfg.categories.filter { it.isMedia && !canRead(ctx, it) }

    fun requestList(cfg: SyncConfig): Array<String> {
        val out = LinkedHashSet<String>()
        val cats = cfg.categories
        if (cfg.useMediaStore) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (SyncCategory.PHOTOS in cats) out.add(Manifest.permission.READ_MEDIA_IMAGES)
                if (SyncCategory.VIDEOS in cats) out.add(Manifest.permission.READ_MEDIA_VIDEO)
                if (SyncCategory.AUDIO in cats) out.add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= 34 && (SyncCategory.PHOTOS in cats || SyncCategory.VIDEOS in cats)) {
                    out.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else if (cats.any { it.isMedia }) {
                out.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 29 && SyncCategory.PHOTOS in cats) out.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) out.add(Manifest.permission.POST_NOTIFICATIONS)
        return out.toTypedArray()
    }
}

// =====================================================================
//  Moteur
// =====================================================================

interface SyncHost {
    /** Vrai si le travail tourne déjà au premier plan (pas de limite de temps). */
    val foreground: Boolean
    suspend fun transferStarted(pending: Int)
    fun onProgress(p: SyncProgress)
}

class SyncOutcome(val summary: String, val more: Boolean = false)

private class SyncItem(
    val key: String,
    val sig: String,
    val uri: Uri,
    val size: Long,
    val dir: List<String>,
    val name: String,
    val keepLocation: Boolean,
)

private class Child(val id: String, val name: String, val dir: Boolean, val size: Long, val mtime: Long)

object SyncEngine {
    private val mutex = Mutex()
    private const val SOFT_LIMIT_MS = 8 * 60_000L

    /** Lance un passage. Renvoie null si un autre passage est déjà en cours. */
    suspend fun run(ctx: Context, host: SyncHost): SyncOutcome? {
        if (!mutex.tryLock()) return null
        val app = ctx.applicationContext
        val cfg = SyncConfig(app)
        try {
            if (!cfg.enabled) return SyncOutcome("Désactivé")
            SyncStatus.progress.value = SyncProgress("Préparation…", 0, 0, -1f)
            val outcome = try {
                withContext(Dispatchers.IO) { execute(app, cfg, host) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                SyncOutcome(if (e.code == 401) "Identifiants refusés : réactivez HomeCloud Sync" else (e.message ?: "Erreur du serveur"))
            } catch (e: Exception) {
                SyncOutcome(e.message ?: "Erreur inattendue")
            }
            val now = System.currentTimeMillis()
            cfg.lastRunAt = now
            cfg.lastSummary = outcome.summary
            SyncStatus.lastRun.value = now to outcome.summary
            SyncLog.add(app, "— ${outcome.summary}")
            return outcome
        } finally {
            SyncStatus.progress.value = null
            mutex.unlock()
        }
    }

    // ---------- session ----------

    private class Session(val api: Api, val user: String, val pass: String) {
        suspend fun <T> call(block: suspend () -> T): T = try {
            block()
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            api.token = api.login(user, pass).first
            block()
        }
    }

    private suspend fun connect(ctx: Context, cfg: SyncConfig, api: Api, pass: String): String? {
        val server = parseAddress(cfg.address) ?: return "Adresse du serveur invalide"
        api.server = server
        try {
            api.token = api.login(cfg.username, pass).first
            return null
        } catch (e: IOException) {
            if (!cfg.autoDiscover || cfg.serverName.isEmpty()) return "Serveur injoignable (${cfg.address})"
        }
        // Repli : même nom de serveur sur le réseau local (jamais « le premier qui répond »).
        val found = ArrayList<Server>()
        Discovery.scan(ctx) { found.add(it) }
        val pick = found.firstOrNull { it.name == cfg.serverName } ?: return "Serveur injoignable (${cfg.address})"
        api.server = pick
        return try {
            api.token = api.login(cfg.username, pass).first
            cfg.address = pick.display
            null
        } catch (e: IOException) {
            "Serveur injoignable (${cfg.address})"
        }
    }

    // ---------- passage complet ----------

    private suspend fun execute(ctx: Context, cfg: SyncConfig, host: SyncHost): SyncOutcome {
        val started = System.currentTimeMillis()
        val pass = cfg.password() ?: return SyncOutcome("Mot de passe introuvable : réactivez HomeCloud Sync")
        if (cfg.diskId.isEmpty()) return SyncOutcome("Aucun disque de destination choisi")

        val api = Api()
        connect(ctx, cfg, api, pass)?.let { return SyncOutcome(it) }
        val session = Session(api, cfg.username, pass)

        val disk = session.call { api.disks() }.firstOrNull { it.id == cfg.diskId }
            ?: return SyncOutcome("Disque de destination introuvable")
        if (!disk.rw) return SyncOutcome("Le disque « ${disk.name} » est en lecture seule")

        val items = collect(ctx, cfg)
        val state = SyncState(ctx, "${cfg.username}|${cfg.diskId}|${cfg.remoteBase}")
        val pending = items.filter { state.map[it.key] != it.sig }
        if (pending.isEmpty()) return SyncOutcome("À jour — ${items.size} fichier(s) suivi(s)")

        host.transferStarted(pending.size)
        val base = cfg.remoteBase
        val dirs = HashMap<String, MutableMap<String, Entry>>()
        var uploaded = 0
        var already = 0
        var skipped = 0
        var errors = 0
        var streak = 0
        var bytes = 0L
        var more = false
        var lastUi = 0L
        val total = pending.size
        var index = 0

        try {
            for (item in pending) {
                currentCoroutineContext().ensureActive()
                if (!host.foreground && System.currentTimeMillis() - started > SOFT_LIMIT_MS) { more = true; break }
                index++
                val dirPath = item.dir.fold(base) { acc, s -> joinPath(acc, s) }
                val path = joinPath(dirPath, item.name)
                val label = item.dir.joinToString("/") + "/" + item.name
                fun report(sent: Long) {
                    val now = System.currentTimeMillis()
                    if (sent < item.size && now - lastUi < 250) return
                    lastUi = now
                    val frac = ((index - 1) + if (item.size > 0) sent.toFloat() / item.size else 0f) / total
                    val p = SyncProgress(label, index, total, frac.coerceIn(0f, 1f))
                    SyncStatus.progress.value = p
                    host.onProgress(p)
                }
                report(0)

                try {
                    val remote = dirs.getOrPut(dirPath) { loadDir(session, api, disk.id, dirPath) }[item.name]
                    when {
                        remote != null && remote.dir -> {
                            skipped++; state.map[item.key] = item.sig
                            SyncLog.add(ctx, "▲ $label : un dossier porte ce nom sur le cloud, ignoré")
                        }
                        remote != null && remote.locked -> {
                            skipped++; state.map[item.key] = item.sig
                            SyncLog.add(ctx, "▲ $label : verrouillé par mot de passe sur le cloud, ignoré")
                        }
                        remote != null && state.map[item.key] == null && remote.size == item.size -> {
                            already++; state.map[item.key] = item.sig
                        }
                        else -> {
                            session.call {
                                api.upload(disk.id, path, "", item.size, { openSource(ctx, item) }, { sent -> report(sent) })
                            }
                            dirs[dirPath]?.put(item.name, Entry(item.name, false, item.size, System.currentTimeMillis() / 1000, false))
                            state.map[item.key] = item.sig
                            uploaded++; bytes += item.size
                            SyncLog.add(ctx, "↑ $label (${humanSize(item.size)})")
                        }
                    }
                    streak = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.locked) {
                        skipped++; state.map[item.key] = item.sig
                        SyncLog.add(ctx, "▲ $label : verrouillé, ignoré")
                    } else {
                        errors++; streak++
                        SyncLog.add(ctx, "✘ $label : ${e.message}")
                    }
                } catch (e: Exception) {
                    errors++; streak++
                    SyncLog.add(ctx, "✘ $label : ${e.message ?: e.javaClass.simpleName}")
                }
                if (streak >= 3) {
                    SyncLog.add(ctx, "▲ Trop d'erreurs consécutives, arrêt de ce passage")
                    break
                }
                if (uploaded > 0 && uploaded % 20 == 0) state.save()
            }
        } finally {
            state.save()
        }

        val parts = ArrayList<String>()
        if (uploaded > 0) parts.add("$uploaded envoyé(s) (${humanSize(bytes)})")
        if (already > 0) parts.add("$already déjà présent(s)")
        if (skipped > 0) parts.add("$skipped ignoré(s)")
        if (errors > 0) parts.add("$errors erreur(s)")
        if (more) parts.add("suite au prochain passage")
        return SyncOutcome(if (parts.isEmpty()) "Rien à envoyer" else parts.joinToString(", "), more)
    }

    private suspend fun loadDir(session: Session, api: Api, diskId: String, path: String): MutableMap<String, Entry> {
        try {
            return session.call { api.list(diskId, path) }.associateByTo(HashMap()) { it.name }
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 403) throw e
        }
        // Le dossier n'existe pas encore : on crée toute la chaîne.
        var cur = ""
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            cur = "$cur/$seg"
            try {
                session.call { api.mkdir(diskId, cur) }
            } catch (e: ApiException) {
                if (e.code != 409) throw e      // 409 = existe déjà
            }
        }
        return HashMap()
    }

    private fun openSource(ctx: Context, item: SyncItem): java.io.InputStream {
        val cr = ctx.contentResolver
        if (item.keepLocation && Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { cr.openInputStream(MediaStore.setRequireOriginal(item.uri)) }.getOrNull()?.let { return it }
        }
        return cr.openInputStream(item.uri) ?: throw IOException("lecture impossible")
    }

    // ---------- inventaire des fichiers de l'appareil ----------

    private suspend fun collect(ctx: Context, cfg: SyncConfig): List<SyncItem> {
        val out = ArrayList<SyncItem>()
        val used = HashSet<String>()

        fun add(key: String, sig: String, uri: Uri, size: Long, cat: SyncCategory, dir: List<String>, name: String, suffix: String, orig: Boolean) {
            val d = listOf(cat.folder) + dir.map { safeFileName(it) }
            var n = safeFileName(name)
            if (!used.add((d + n).joinToString("/").lowercase())) {
                val stem = n.substringBeforeLast('.', n)
                val ext = n.substringAfterLast('.', "")
                n = if (ext.isEmpty()) "${stem}_$suffix" else "${stem}_$suffix.$ext"
                used.add((d + n).joinToString("/").lowercase())
            }
            out.add(SyncItem(key, sig, uri, size, d, n, orig))
        }

        // 1) Galerie de l'appareil (MediaStore)
        if (cfg.useMediaStore) {
            val sources = listOf(
                SyncCategory.PHOTOS to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                SyncCategory.VIDEOS to MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                SyncCategory.AUDIO to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            )
            for ((cat, base) in sources) {
                if (cat !in cfg.categories) continue
                if (!SyncPerms.canRead(ctx, cat)) { SyncLog.add(ctx, "▲ ${cat.label} : accès non autorisé, ignoré"); continue }
                currentCoroutineContext().ensureActive()
                val cursor = try { queryMedia(ctx, base, true) } catch (e: Exception) { runCatching { queryMedia(ctx, base, false) }.getOrNull() }
                cursor?.use { c ->
                    val hasBucket = c.columnCount > 4
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val size = if (c.isNull(2)) -1L else c.getLong(2)
                        if (size < 0) continue
                        val mtime = if (c.isNull(3)) 0L else c.getLong(3)
                        val bucket = (if (hasBucket) c.getString(4) else null)?.takeIf { it.isNotBlank() } ?: cat.label
                        add(
                            "m:${cat.name}:$id", "$size:$mtime", ContentUris.withAppendedId(base, id), size,
                            cat, listOf(bucket), name, id.toString(), cat == SyncCategory.PHOTOS,
                        )
                    }
                }
            }
        }

        // 2) Dossiers choisis (sélecteur système)
        for (f in cfg.folders) {
            currentCoroutineContext().ensureActive()
            scanTree(ctx, f, cfg) { key, sig, uri, size, cat, dir, name, suffix -> add(key, sig, uri, size, cat, dir, name, suffix, false) }
        }
        return out
    }

    private fun queryMedia(ctx: Context, base: Uri, withBucket: Boolean) = ctx.contentResolver.query(
        base,
        if (withBucket) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, "bucket_display_name")
        else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
        if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.IS_PENDING}=0" else null,
        null,
        "${MediaStore.MediaColumns._ID} ASC",
    )

    private suspend fun scanTree(
        ctx: Context,
        folder: SyncFolder,
        cfg: SyncConfig,
        add: (key: String, sig: String, uri: Uri, size: Long, cat: SyncCategory, dir: List<String>, name: String, suffix: String) -> Unit,
    ) {
        val tree = Uri.parse(folder.uri)
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) {
            SyncLog.add(ctx, "▲ Dossier « ${folder.label} » invalide, ignoré"); return
        }
        // Les médias de la galerie sont déjà pris par MediaStore : on évite les doublons.
        val skipMedia = cfg.useMediaStore

        suspend fun walk(docId: String, rel: List<String>, depth: Int): Unit {
            currentCoroutineContext().ensureActive()
            val kids = ArrayList<Child>()
            try {
                ctx.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        kids.add(
                            Child(
                                id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                                if (c.isNull(3)) -1L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                SyncLog.add(ctx, "▲ « ${folder.label}${if (rel.isEmpty()) "" else "/" + rel.joinToString("/")} » illisible : ${e.message ?: "accès refusé"}")
                return
            }
            for (k in kids) {
                if (k.name.startsWith(".")) continue
                if (k.dir) {
                    if (depth < 24) walk(k.id, rel + k.name, depth + 1)
                    continue
                }
                if (k.size < 0) continue
                val cat = cfg.match(k.name.substringAfterLast('.', "")) ?: continue
                if (skipMedia && cat.isMedia) continue
                add(
                    "d:${folder.uri}|${k.id}", "${k.size}:${k.mtime}",
                    DocumentsContract.buildDocumentUriUsingTree(tree, k.id), k.size, cat,
                    listOf(folder.label) + rel, k.name, Integer.toHexString(k.id.hashCode()),
                )
            }
        }
        walk(rootId, emptyList(), 0)
    }
}
