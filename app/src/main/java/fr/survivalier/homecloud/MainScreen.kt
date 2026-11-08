package fr.survivalier.homecloud

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

fun diskLabel(d: Disk, me: String?): String = when {
    d.personal && d.owner == me -> "Mon espace"
    d.personal -> "Espace de ${d.owner}"
    else -> d.name
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showMkdir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Entry?>(null) }
    var lockTarget by remember { mutableStateOf<Entry?>(null) }
    var detailsTarget by remember { mutableStateOf<Entry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        vm.finishSave(uri)
    }
    LaunchedEffect(vm.saveRequest) { vm.saveRequest?.let { createDoc.launch(it.entry.name) } }

    BackHandler(enabled = vm.selected.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = vm.selected.isEmpty() && vm.path != "/") { vm.goUp() }

    val sel = vm.selectedEntries()
    val rw = vm.disk?.rw == true

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(vm.avatar, vm.user?.name ?: "?", 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(vm.user?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(vm.serverDisplay, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Text(
                    "DISQUES", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    vm.disks.forEach { d ->
                        NavigationDrawerItem(
                            selected = d.id == vm.disk?.id,
                            onClick = { vm.selectDisk(d); scope.launch { drawerState.close() } },
                            icon = { DiskIcon(d.personal) },
                            label = {
                                Column {
                                    Text(diskLabel(d, vm.user?.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (d.size > 0) {
                                        LinearProgressIndicator(
                                            progress = { (d.used.toFloat() / d.size).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        )
                                        Text(
                                            "${humanSize(d.used)} / ${humanSize(d.size)}" + if (!d.rw) " · lecture seule" else "",
                                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    selected = false, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Compte et paramètres") },
                    onClick = { scope.launch { drawerState.close() }; vm.openSettings() }, modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (vm.isAdmin) {
                    NavigationDrawerItem(
                        selected = false, icon = { Icon(Icons.Filled.AdminPanelSettings, null) }, label = { Text("Administration") },
                        onClick = { scope.launch { drawerState.close() }; vm.openAdmin() }, modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    selected = false, icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, label = { Text("Déconnexion") },
                    onClick = { scope.launch { drawerState.close() }; vm.logout() }, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (sel.isNotEmpty()) {
                    TopAppBar(
                        title = { Text("${sel.size} sélectionné(s)") },
                        navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Annuler la sélection") } },
                        actions = {
                            if (rw) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Supprimer") }
                            if (sel.size == 1) {
                                val e = sel[0]
                                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "Plus d'actions") }
                                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                    if (!e.dir) {
                                        DropdownMenuItem(
                                            text = { Text("Ouvrir avec…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                            onClick = { moreMenu = false; vm.openFile(e, false) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Partager") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                                            onClick = { moreMenu = false; vm.openFile(e, true) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Enregistrer sur l'appareil") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                                            onClick = { moreMenu = false; vm.requestDownload(e) },
                                        )
                                        if (rw) DropdownMenuItem(
                                            text = { Text("Modifier le texte") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                            onClick = { moreMenu = false; vm.editText(e) },
                                        )
                                    }
                                    if (rw) DropdownMenuItem(
                                        text = { Text("Renommer") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { moreMenu = false; renameTarget = e },
                                    )
                                    if (rw && !e.dir) {
                                        if (e.locked) DropdownMenuItem(
                                            text = { Text("Déverrouiller") }, leadingIcon = { Icon(Icons.Filled.LockOpen, null) },
                                            onClick = { moreMenu = false; vm.unlockEntry(e) },
                                        ) else DropdownMenuItem(
                                            text = { Text("Verrouiller…") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                            onClick = { moreMenu = false; lockTarget = e },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Détails") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                                        onClick = { moreMenu = false; detailsTarget = e },
                                    )
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            val d = vm.disk
                            Text(
                                if (vm.path == "/") (d?.let { diskLabel(it, vm.user?.name) } ?: "Home Cloud") else vm.path.substringAfterLast('/'),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (vm.path != "/") IconButton(onClick = { vm.goUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Dossier parent") }
                            else IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "Disques") }
                        },
                        actions = {
                            IconButton(onClick = { vm.reloadDisks() }) { Icon(Icons.Filled.Refresh, "Actualiser") }
                            IconButton(onClick = { vm.openSettings() }) { AvatarView(vm.avatar, vm.user?.name ?: "?", 32.dp) }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (rw && sel.isEmpty()) {
                    Box {
                        FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Filled.Add, "Ajouter") }
                        DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Importer des fichiers") }, leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                                onClick = { fabMenu = false; pickFiles.launch(arrayOf("*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text("Nouveau dossier") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                onClick = { fabMenu = false; showMkdir = true },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Breadcrumb(vm)
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.progress?.let { p ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(p.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.fraction >= 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        vm.disk == null && !vm.loading ->
                            CenterText("Aucun disque accessible.\nDemandez à l'administrateur de vous donner un accès.")
                        vm.listError != null -> CenterText(vm.listError ?: "", error = true)
                        vm.entries.isEmpty() && !vm.loading -> CenterText("Dossier vide")
                        else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(vm.entries, key = { it.name }) { e ->
                                val isSel = e.name in vm.selected
                                ListItem(
                                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        val sub = listOfNotNull(
                                            if (!e.dir) humanSize(e.size) else null,
                                            if (e.mtime > 0) formatDate(e.mtime) else null,
                                        ).joinToString(" · ")
                                        if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp)
                                    },
                                    leadingContent = {
                                        val isSync = e.dir && vm.disk?.id == vm.syncCfg.diskId &&
                                            SyncConfig.isSyncFolder(joinPath(vm.path, e.name), vm.syncCfg.remoteBase)
                                        FileIcon(e, sync = isSync)
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    ),
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            when {
                                                vm.selected.isNotEmpty() -> vm.toggleSelect(e.name)
                                                e.dir -> vm.openDir(e.name)
                                                else -> vm.openFile(e, false)
                                            }
                                        },
                                        onLongClick = { vm.toggleSelect(e.name) },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMkdir) TextDialog(
        "Nouveau dossier", "Nom du dossier", confirm = "Créer",
        onDismiss = { showMkdir = false }, onConfirm = { showMkdir = false; vm.mkdir(it) },
    )
    renameTarget?.let { e ->
        TextDialog(
            "Renommer", "Nouveau nom", initial = e.name, confirm = "Renommer",
            onDismiss = { renameTarget = null }, onConfirm = { renameTarget = null; vm.rename(e, it) },
        )
    }
    lockTarget?.let { e ->
        SetLockDialog(e.name, onDismiss = { lockTarget = null }, onConfirm = { lockTarget = null; vm.lockEntry(e, it) })
    }
    if (confirmDelete) {
        val n = sel.size
        val locked = sel.count { it.locked }
        ConfirmDialog(
            title = "Supprimer",
            text = (if (n == 1) "Supprimer « ${sel[0].name} » ?" else "Supprimer ces $n éléments ?") +
                (if (sel.any { it.dir }) "\nLe contenu des dossiers sera supprimé." else "") +
                (if (locked > 0 && !vm.isAdmin) "\nLe mot de passe des fichiers verrouillés vous sera demandé." else ""),
            confirm = "Supprimer", danger = true,
            onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; vm.deleteSelected() },
        )
    }
    detailsTarget?.let { e -> DetailsDialog(vm, e) { detailsTarget = null } }
}

@Composable
private fun CenterText(text: String, error: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun Breadcrumb(vm: MainViewModel) {
    val segs = vm.path.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.goTo("/") }) { Text(vm.disk?.let { diskLabel(it, vm.user?.name) } ?: "—") }
        var acc = ""
        segs.forEach { s ->
            acc += "/$s"
            val target = acc
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.goTo(target) }) { Text(s) }
        }
    }
}

@Composable
private fun DetailsDialog(vm: MainViewModel, e: Entry, onClose: () -> Unit) {
    var info by remember(e) { mutableStateOf<FileInfo?>(null) }
    LaunchedEffect(e) { info = vm.fileInfo(e) }

    @Composable
    fun row(label: String, value: String) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value)
        }
    }

    val syncIcon = e.dir && vm.disk?.id == vm.syncCfg.diskId &&
        SyncConfig.isSyncFolder(joinPath(vm.path, e.name), vm.syncCfg.remoteBase)
    AlertDialog(
        onDismissRequest = onClose,
        icon = { FileIcon(e, 48.dp, sync = syncIcon) },
        title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                row("Type", if (e.dir) "Dossier" else "Fichier")
                if (!e.dir) row("Taille", "${humanSize(e.size)} (${e.size} octets)")
                if (e.mtime > 0) row("Modifié", formatDate(e.mtime))
                info?.let { i ->
                    if (i.ctime > 0) row("Créé / changé", formatDate(i.ctime))
                    if (e.locked) row("Protection", "Verrouillé par mot de passe" + (i.lockOwner?.takeIf { it.isNotBlank() }?.let { " (par $it)" } ?: ""))
                }
                if (info == null && e.locked) row("Protection", "Verrouillé par mot de passe")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
    )
}
