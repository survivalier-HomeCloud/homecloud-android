package fr.survivalier.homecloud

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

enum class Page { LOGIN, MAIN, SETTINGS, ADMIN, EDITOR, SYNC }

sealed interface UiEvent {
    class Toast(val msg: String) : UiEvent
    class Open(val uri: Uri, val mime: String, val share: Boolean) : UiEvent
}

class Progress(val label: String, val fraction: Float)

/** Demande de mot de passe d'un fichier verrouillé (ouverture, suppression, déverrouillage…). */
class LockPrompt(
    val title: String,
    val fileName: String,
    val error: String?,
    val confirmLabel: String,
    val onSubmit: (String) -> Unit,
    val onCancel: () -> Unit,
)

class SaveRequest(val entry: Entry, val pass: String, val diskId: String, val fullPath: String)

class EditorDoc(val name: String, val diskId: String, val path: String, val pass: String, initial: String) {
    var text by mutableStateOf(initial)
    var saved by mutableStateOf(initial)
    var saving by mutableStateOf(false)
    val dirty: Boolean get() = text != saved
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val ctx get() = getApplication<Application>()
    val api = Api()

    var page by mutableStateOf(Page.LOGIN); private set
    var booting by mutableStateOf(true); private set
    val events = Channel<UiEvent>(Channel.BUFFERED)

    // ---- HomeCloud Sync ----
    val syncCfg = SyncConfig(app)
    var syncRev by mutableStateOf(0); private set

    // ---- thème ----
    var themeMode by mutableStateOf(prefs.theme); private set
    fun setTheme(mode: String) { themeMode = mode; prefs.theme = mode }

    // ---- connexion ----
    val found = mutableStateListOf<Server>()
    var scanning by mutableStateOf(false); private set
    var loginBusy by mutableStateOf(false); private set
    var loginError by mutableStateOf<String?>(null)
    val savedAddress: String get() = prefs.address
    val savedUsername: String get() = prefs.username

    // ---- session ----
    var user by mutableStateOf<User?>(null); private set
    var avatar by mutableStateOf<ImageBitmap?>(null); private set
    val isAdmin: Boolean get() = user?.admin == true
    val serverDisplay: String get() = api.server?.display ?: ""

    // ---- explorateur ----
    val disks = mutableStateListOf<Disk>()
    var disk by mutableStateOf<Disk?>(null); private set
    var path by mutableStateOf("/"); private set
    val entries = mutableStateListOf<Entry>()
    var loading by mutableStateOf(false); private set
    var listError by mutableStateOf<String?>(null); private set
    val selected = mutableStateListOf<String>()
    var progress by mutableStateOf<Progress?>(null); private set
    var lockPrompt by mutableStateOf<LockPrompt?>(null); private set
    var saveRequest by mutableStateOf<SaveRequest?>(null); private set
    var editor by mutableStateOf<EditorDoc?>(null); private set
    private var navSeq = 0

    // ---- administration ----
    val adminUsers = mutableStateListOf<AdminUser>()
    var permsFor by mutableStateOf<Disk?>(null); private set
    val permMap = mutableStateMapOf<String, String>()

    init {
        File(ctx.cacheDir, "open").deleteRecursively()
        SyncScheduler.apply(ctx, replace = false)
        val srv = parseAddress(prefs.address)
        if (srv != null && prefs.token.isNotEmpty()) {
            api.server = srv
            api.token = prefs.token
            viewModelScope.launch {
                try {
                    startSession(api.me())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    api.token = ""
                    prefs.token = ""
                    page = Page.LOGIN
                    startScan()
                } finally {
                    booting = false
                }
            }
        } else {
            booting = false
            startScan()
        }
    }

    // ================= helpers =================

    private fun toast(msg: String) { events.trySend(UiEvent.Toast(msg)) }

    /** Lance une opération réseau avec gestion uniforme des erreurs (session expirée, réseau…). */
    private fun launchApi(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.code == 401 && user != null) {
                toast("Session expirée, reconnectez-vous")
                endSession()
            } else toast(e.message ?: "Erreur")
        } catch (e: IOException) {
            toast("Connexion impossible : ${e.message ?: "erreur réseau"}")
        } catch (e: Exception) {
            toast(e.message ?: "Erreur inattendue")
        }
    }

    private fun setProgress(label: String, done: Long, total: Long) {
        val f = if (total > 0) done.toFloat() / total else -1f
        val cur = progress
        if (cur == null || cur.label != label || f < 0 || kotlin.math.abs(f - cur.fraction) >= 0.01f) progress = Progress(label, f)
    }

    // ================= connexion =================

    fun startScan() {
        if (scanning) return
        scanning = true
        found.clear()
        viewModelScope.launch {
            try {
                Discovery.scan(ctx) { s -> viewModelScope.launch { if (found.none { it.host == s.host && it.port == s.port }) found.add(s) } }
            } finally {
                scanning = false
            }
        }
    }

    fun login(address: String, username: String, password: String) {
        val srv = parseAddress(address)
        when {
            srv == null -> { loginError = "Adresse du serveur invalide"; return }
            username.isBlank() || password.isEmpty() -> { loginError = "Identifiant et mot de passe requis"; return }
        }
        loginBusy = true
        loginError = null
        viewModelScope.launch {
            try {
                api.server = srv
                api.token = ""
                val (token, u) = api.login(username.trim(), password)
                api.token = token
                prefs.address = srv!!.display
                prefs.username = username.trim()
                prefs.token = token
                startSession(u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                loginError = e.message
            } catch (e: IOException) {
                loginError = "Serveur injoignable (${srv!!.display})"
            } catch (e: Exception) {
                loginError = e.message ?: "Erreur inattendue"
            } finally {
                loginBusy = false
            }
        }
    }

    private suspend fun startSession(u: User) {
        user = u
        page = Page.MAIN
        viewModelScope.launch { loadAvatar() }
        try {
            loadDisks(keepCurrent = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Impossible de lister les disques : ${e.message ?: "erreur"}")
        }
    }

    private fun endSession() {
        api.token = ""
        prefs.token = ""
        user = null
        avatar = null
        disks.clear()
        disk = null
        entries.clear()
        selected.clear()
        path = "/"
        editor = null
        progress = null
        lockPrompt = null
        saveRequest = null
        page = Page.LOGIN
        startScan()
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { api.logout() }
            endSession()
        }
    }

    private suspend fun loadAvatar() {
        val name = user?.name ?: return
        avatar = runCatching {
            api.avatar(name)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }.getOrNull()
    }

    // ================= navigation =================

    fun goMain() { page = Page.MAIN }
    fun openSettings() { page = Page.SETTINGS }
    fun openAdmin() { page = Page.ADMIN; loadAdmin() }
    fun openSync() { page = Page.SYNC; reloadDisks() }

    private suspend fun loadDisks(keepCurrent: Boolean) {
        val list = api.disks()
        disks.clear()
        disks.addAll(list)
        val prev = disk
        val next = (if (keepCurrent && prev != null) list.firstOrNull { it.id == prev.id } else null) ?: list.firstOrNull()
        if (next?.id != prev?.id) path = "/"
        disk = next
        if (next != null) refresh() else entries.clear()
    }

    fun reloadDisks() { launchApi { loadDisks(keepCurrent = true) } }

    fun selectDisk(d: Disk) {
        page = Page.MAIN
        disk = d
        path = "/"
        selected.clear()
        refresh()
    }

    fun openDir(name: String) { path = joinPath(path, name); selected.clear(); refresh() }

    fun goTo(p: String) { path = p; selected.clear(); refresh() }

    fun goUp() { if (path != "/") goTo(parentPath(path)) }

    fun refresh() {
        val d = disk ?: return
        val p = path
        val tk = ++navSeq
        loading = true
        listError = null
        launchApi {
            try {
                val list = api.list(d.id, p)
                if (tk != navSeq) return@launchApi
                entries.clear()
                entries.addAll(list.sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() }))
                selected.removeAll { n -> entries.none { it.name == n } }
            } catch (e: ApiException) {
                if (e.code == 401) throw e
                if (tk == navSeq) { entries.clear(); listError = e.message }
            } finally {
                if (tk == navSeq) loading = false
            }
        }
    }

    fun toggleSelect(name: String) { if (!selected.remove(name)) selected.add(name) }
    fun clearSelection() { selected.clear() }
    fun selectedEntries(): List<Entry> = entries.filter { it.name in selected }

    // ================= verrous =================

    /** Demande (et vérifie) le mot de passe d'un fichier verrouillé. onResult("") = aucun mot de passe nécessaire, null = annulé. */
    private fun requirePass(entry: Entry, err: String?, onResult: (String?) -> Unit) {
        val diskId = disk?.id ?: return
        if (!entry.locked || entry.dir || isAdmin) { onResult(""); return }
        val full = joinPath(path, entry.name)
        lockPrompt = LockPrompt(
            title = "Fichier verrouillé", fileName = entry.name, error = err, confirmLabel = "Valider",
            onSubmit = { pass ->
                lockPrompt = null
                launchApi {
                    try {
                        api.lockCheck(diskId, full, pass)
                        onResult(pass)
                    } catch (e: ApiException) {
                        if (e.locked) requirePass(entry, e.message, onResult) else throw e
                    }
                }
            },
            onCancel = { lockPrompt = null; onResult(null) },
        )
    }

    private fun collectPasses(targets: List<Entry>, done: (Map<String, String>?) -> Unit) {
        val need = if (isAdmin) emptyList() else targets.filter { it.locked && !it.dir }
        val acc = HashMap<String, String>()
        fun next(i: Int) {
            if (i >= need.size) { done(acc); return }
            requirePass(need[i], null) { p ->
                if (p == null) done(null) else { acc[need[i].name] = p; next(i + 1) }
            }
        }
        next(0)
    }

    fun lockEntry(entry: Entry, pass: String) {
        val d = disk ?: return
        val full = joinPath(path, entry.name)
        launchApi {
            api.lock(d.id, full, pass)
            toast("Fichier verrouillé")
            selected.clear()
            refresh()
        }
    }

    fun unlockEntry(entry: Entry) {
        val d = disk ?: return
        val full = joinPath(path, entry.name)
        fun ask(err: String?) {
            lockPrompt = LockPrompt(
                title = "Déverrouiller", fileName = entry.name, error = err, confirmLabel = "Déverrouiller",
                onSubmit = { pass ->
                    lockPrompt = null
                    launchApi {
                        try {
                            api.unlock(d.id, full, pass)
                            toast("Fichier déverrouillé")
                            selected.clear()
                            refresh()
                        } catch (e: ApiException) {
                            if (e.locked) ask(e.message) else throw e
                        }
                    }
                },
                onCancel = { lockPrompt = null },
            )
        }
        if (isAdmin) {
            launchApi { api.unlock(d.id, full, ""); toast("Fichier déverrouillé"); selected.clear(); refresh() }
        } else ask(null)
    }

    // ================= opérations sur les fichiers =================

    fun mkdir(name: String) {
        val d = disk ?: return
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) { toast("Nom de dossier invalide"); return }
        val full = joinPath(path, n)
        launchApi { api.mkdir(d.id, full); refresh() }
    }

    fun deleteSelected() {
        val d = disk ?: return
        val dir = path
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        collectPasses(targets) { passes ->
            if (passes == null) return@collectPasses
            launchApi {
                for (e in targets) {
                    try {
                        api.delete(d.id, joinPath(dir, e.name), passes[e.name] ?: "")
                    } catch (ex: ApiException) {
                        if (ex.code == 401) throw ex
                        toast("${e.name} : ${ex.message}")
                    }
                }
                selected.clear()
                refresh()
            }
        }
    }

    fun rename(entry: Entry, newName: String) {
        val d = disk ?: return
        val n = newName.trim()
        if (n.isEmpty() || n.contains('/')) { toast("Nom invalide"); return }
        if (n == entry.name) return
        val from = joinPath(path, entry.name)
        val to = joinPath(path, n)
        collectPasses(listOf(entry)) { passes ->
            if (passes == null) return@collectPasses
            launchApi {
                api.rename(d.id, from, to, passes[entry.name] ?: "")
                selected.clear()
                refresh()
            }
        }
    }

    suspend fun fileInfo(entry: Entry): FileInfo? {
        val d = disk ?: return null
        return try { api.info(d.id, joinPath(path, entry.name)) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    // ---- ouverture / partage ----

    private suspend fun downloadTo(diskId: String, full: String, name: String, pass: String, out: OutputStream) {
        val label = "Téléchargement : $name"
        setProgress(label, 0, -1)
        try {
            out.use { api.download(diskId, full, pass, it) { done, total -> setProgress(label, done, total) } }
        } finally {
            progress = null
        }
    }

    fun openFile(entry: Entry, share: Boolean) {
        val d = disk ?: return
        val full = joinPath(path, entry.name)
        collectPasses(listOf(entry)) { passes ->
            if (passes == null) return@collectPasses
            launchApi {
                val dir = File(ctx.cacheDir, "open/${System.nanoTime()}").apply { mkdirs() }
                val f = File(dir, safeFileName(entry.name))
                withContext(Dispatchers.IO) { f.parentFile?.mkdirs() }
                downloadTo(d.id, full, entry.name, passes[entry.name] ?: "", f.outputStream())
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                events.send(UiEvent.Open(uri, guessMime(entry.name), share))
            }
        }
    }

    // ---- enregistrer sur l'appareil (sélecteur de document système) ----

    fun requestDownload(entry: Entry) {
        val d = disk ?: return
        val full = joinPath(path, entry.name)
        collectPasses(listOf(entry)) { passes ->
            if (passes == null) return@collectPasses
            saveRequest = SaveRequest(entry, passes[entry.name] ?: "", d.id, full)
        }
    }

    fun finishSave(uri: Uri?) {
        val r = saveRequest ?: return
        saveRequest = null
        if (uri == null) return
        launchApi {
            val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Écriture impossible")
            downloadTo(r.diskId, r.fullPath, r.entry.name, r.pass, out)
            toast("Fichier enregistré")
        }
    }

    // ---- éditeur de texte intégré ----

    fun editText(entry: Entry) {
        val d = disk ?: return
        if (entry.size > EDIT_MAX) { toast("Fichier trop volumineux pour l'éditeur intégré (${humanSize(EDIT_MAX)} max)"); return }
        val full = joinPath(path, entry.name)
        collectPasses(listOf(entry)) { passes ->
            if (passes == null) return@collectPasses
            val pass = passes[entry.name] ?: ""
            launchApi {
                val bos = ByteArrayOutputStream()
                downloadTo(d.id, full, entry.name, pass, bos)
                val bytes = bos.toByteArray()
                if (bytes.any { it == 0.toByte() }) { toast("Ce fichier n'est pas un fichier texte"); return@launchApi }
                editor = EditorDoc(entry.name, d.id, full, pass, String(bytes, Charsets.UTF_8))
                page = Page.EDITOR
            }
        }
    }

    fun saveEditor() {
        val e = editor ?: return
        if (e.saving) return
        val txt = e.text
        val bytes = txt.toByteArray(Charsets.UTF_8)
        e.saving = true
        launchApi {
            try {
                api.upload(e.diskId, e.path, e.pass, bytes.size.toLong(), { ByteArrayInputStream(bytes) }, {})
                e.saved = txt
                toast("Enregistré sur Home Cloud")
            } finally {
                e.saving = false
            }
        }
    }

    fun closeEditor() { editor = null; page = Page.MAIN; refresh() }

    // ---- import ----

    private class Picked(val uri: Uri, val name: String, val size: Long)

    private fun queryPicked(uri: Uri): Picked {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        return Picked(uri, safeFileName(name), size)
    }

    fun upload(uris: List<Uri>) {
        val d = disk ?: return
        if (!d.rw) { toast("Ce disque est en lecture seule"); return }
        val dir = path
        val items = uris.map { queryPicked(it) }
        val lockedTargets = items.mapNotNull { it2 -> entries.firstOrNull { e -> e.name == it2.name && e.locked && !e.dir } }
        collectPasses(lockedTargets) { passes ->
            if (passes == null) return@collectPasses
            launchApi {
                var okCount = 0
                for ((i, it2) in items.withIndex()) {
                    val label = "Envoi ${i + 1}/${items.size} : ${it2.name}"
                    setProgress(label, 0, -1)
                    var temp: File? = null
                    try {
                        var size = it2.size
                        if (size < 0) {
                            // taille inconnue : copie temporaire pour fournir un Content-Length
                            val tmp = File(ctx.cacheDir, "open/up_${System.nanoTime()}").also { f -> f.parentFile?.mkdirs() }
                            withContext(Dispatchers.IO) {
                                ctx.contentResolver.openInputStream(it2.uri)?.use { input -> tmp.outputStream().use { o -> input.copyTo(o) } }
                            }
                            temp = tmp
                            size = tmp.length()
                        }
                        val src = temp
                        api.upload(
                            d.id, joinPath(dir, it2.name), passes[it2.name] ?: "", size,
                            { src?.inputStream() ?: ctx.contentResolver.openInputStream(it2.uri) ?: throw IOException("Lecture impossible") },
                            { sent -> setProgress(label, sent, size) },
                        )
                        okCount++
                    } catch (ex: ApiException) {
                        if (ex.code == 401) throw ex
                        toast("${it2.name} : ${ex.message}")
                    } catch (ex: IOException) {
                        toast("${it2.name} : ${ex.message ?: "échec du transfert"}")
                    } finally {
                        temp?.delete()
                    }
                }
                progress = null
                if (okCount > 0) toast(if (okCount == 1) "1 fichier importé" else "$okCount fichiers importés")
                refresh()
                loadDisksQuiet()
            }
        }
    }

    private suspend fun loadDisksQuiet() {
        runCatching {
            val list = api.disks()
            disks.clear(); disks.addAll(list)
            val cur = disk
            if (cur != null) list.firstOrNull { it.id == cur.id }?.let { disk = it }
        }
    }

    // ================= compte =================

    fun changePassword(old: String, new: String) {
        if (new.length < 8) { toast("Nouveau mot de passe trop court (8 caractères minimum)"); return }
        launchApi {
            api.password(old, new)
            // HomeCloud Sync se reconnecte seul : on garde son mot de passe à jour.
            if (syncCfg.enabled && syncCfg.username == user?.name) syncCfg.setPassword(new)
            toast("Mot de passe modifié")
        }
    }

    fun setAvatar(uri: Uri) {
        launchApi {
            val bytes = withContext(Dispatchers.IO) { encodeAvatar(uri) }
            api.setAvatar(bytes)
            avatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            toast("Photo de profil mise à jour")
        }
    }

    private fun encodeAvatar(uri: Uri): ByteArray {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Image illisible")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    // ================= HomeCloud Sync =================

    /** Modifie un réglage de HomeCloud Sync, rafraîchit l'écran et re-programme les passages. */
    fun syncUpdate(block: SyncConfig.() -> Unit) {
        syncCfg.block()
        syncRev++
        SyncScheduler.apply(ctx)
    }

    fun syncEnable(password: String) {
        val srv = api.server
        val name = user?.name
        if (srv == null || name == null) { toast("Connectez-vous d'abord"); return }
        viewModelScope.launch {
            try {
                // Vérifie le mot de passe sur une session à part (sans toucher à la session de l'appli).
                val check = Api().also { it.server = srv }
                withContext(Dispatchers.IO) { check.login(name, password) }
                syncCfg.address = srv.display
                syncCfg.username = name
                syncCfg.serverName = srv.name
                syncCfg.setPassword(password)
                if (syncCfg.diskId.isEmpty() || disks.none { it.id == syncCfg.diskId }) {
                    (disks.firstOrNull { it.personal && it.rw } ?: disks.firstOrNull { it.rw })?.let {
                        syncCfg.diskId = it.id
                        syncCfg.diskName = if (it.personal) "Mon espace" else it.name
                    }
                }
                syncCfg.enabled = true
                syncRev++
                SyncScheduler.apply(ctx)
                if (syncCfg.diskId.isEmpty()) toast("HomeCloud Sync activé : choisissez un disque de destination")
                else toast("HomeCloud Sync activé")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                toast(if (e.code == 401) "Mot de passe incorrect" else (e.message ?: "Erreur"))
            } catch (e: IOException) {
                toast("Serveur injoignable : ${e.message ?: "erreur réseau"}")
            } catch (e: Exception) {
                toast(e.message ?: "Erreur inattendue")
            }
        }
    }

    fun syncDisable() {
        syncCfg.enabled = false
        syncCfg.clearCredentials()
        syncRev++
        SyncScheduler.apply(ctx)
        toast("HomeCloud Sync désactivé")
    }

    fun syncNow() {
        if (!syncCfg.enabled) return
        if (syncCfg.diskId.isEmpty()) { toast("Choisissez d'abord un disque de destination"); return }
        SyncScheduler.runNow(ctx)
        toast("Synchronisation lancée")
    }

    fun syncAddFolder(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val current = syncCfg.folders
        if (current.any { it.uri == uri.toString() }) { toast("Dossier déjà ajouté"); return }
        val stem = safeFileName(treeLabel(uri))
        var label = stem
        var n = 2
        while (current.any { it.label.equals(label, ignoreCase = true) }) { label = "$stem ($n)"; n++ }
        syncUpdate { folders = current + SyncFolder(uri.toString(), label) }
    }

    fun syncRemoveFolder(f: SyncFolder) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(f.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        syncUpdate { folders = folders.filter { it.uri != f.uri } }
    }

    fun syncResetMemory() {
        SyncState.reset(ctx)
        toast("Mémoire réinitialisée")
    }

    private fun treeLabel(tree: Uri): String = runCatching {
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(doc, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Dossier"

    // ================= administration =================

    fun loadAdmin() {
        launchApi {
            adminUsers.clear()
            adminUsers.addAll(api.adminUsers())
            val list = api.disks()
            disks.clear(); disks.addAll(list)
        }
    }

    fun createUser(name: String, pass: String, quotaGb: Int, admin: Boolean) {
        launchApi { api.createUser(name.trim(), pass, quotaGb, admin); toast("Utilisateur créé"); loadAdmin() }
    }

    fun deleteUser(name: String) {
        launchApi { api.deleteUser(name); toast("Utilisateur supprimé"); loadAdmin() }
    }

    fun createDisk(name: String, sizeGb: Int) {
        launchApi { api.createDisk(name.trim(), sizeGb); toast("Disque créé"); loadAdmin() }
    }

    fun deleteDisk(d: Disk) {
        launchApi {
            api.deleteDisk(d.id)
            toast("Disque supprimé")
            if (disk?.id == d.id) disk = null
            loadAdmin()
            loadDisks(keepCurrent = true)
        }
    }

    fun openPerms(d: Disk) {
        launchApi {
            val perms = api.perms(d.id)
            permMap.clear()
            perms.forEach { permMap[it.user] = if (it.rw) "rw" else "r" }
            permsFor = d
        }
    }

    fun closePerms() { permsFor = null }

    fun setPerm(user: String, perm: String) {
        val d = permsFor ?: return
        launchApi {
            api.setPerm(d.id, user, perm)
            if (perm == "none") permMap.remove(user) else permMap[user] = perm
        }
    }

    companion object {
        const val EDIT_MAX = 512_000L
    }
}
