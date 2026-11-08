package fr.survivalier.homecloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val intervals = listOf(
    15 to "15 minutes", 30 to "30 minutes", 60 to "1 heure", 180 to "3 heures",
    360 to "6 heures", 720 to "12 heures", 1440 to "24 heures",
)

private fun intervalLabel(min: Int) = intervals.firstOrNull { it.first == min }?.second ?: "$min minutes"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val rev = vm.syncRev                       // lu pour se recomposer à chaque changement de réglage
    val cfg = vm.syncCfg
    val progress by SyncStatus.progress.collectAsState()
    val logRev by SyncStatus.logRev.collectAsState()
    val lastRun by SyncStatus.lastRun.collectAsState()
    var permTick by remember { mutableStateOf(0) }
    var dlg by remember { mutableStateOf("") }

    val missing = remember(rev, permTick) { SyncPerms.missing(ctx, cfg) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.syncAddFolder(uri)
    }

    val cats = cfg.categories
    val last = lastRun ?: if (cfg.lastRunAt > 0) cfg.lastRunAt to cfg.lastSummary else null
    val log = remember(logRev) { SyncLog.tail(ctx, 30) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HomeCloud Sync") },
                navigationIcon = { IconButton(onClick = { vm.openSettings() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Activer HomeCloud Sync", fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Text(
                        if (cfg.enabled) "Envoi automatique vers « ${cfg.diskName.ifEmpty { "votre cloud" }} »"
                        else "Sauvegarde automatique de vos photos, vidéos et documents",
                    )
                },
                leadingContent = { Icon(Icons.Filled.Sync, null) },
                trailingContent = {
                    Switch(checked = cfg.enabled, onCheckedChange = { on -> if (on) { dlg = "enable" } else { vm.syncDisable() } })
                },
            )

            if (cfg.enabled) {
                if (missing.isNotEmpty()) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Warning, null)
                                Text("Accès requis : ${missing.joinToString { it.label }}", fontWeight = FontWeight.SemiBold)
                            }
                            Text("Autorisez la lecture de ces fichiers pour qu'ils soient synchronisés.", fontSize = 13.sp)
                            Button(onClick = { permLauncher.launch(SyncPerms.requestList(cfg)) }) { Text("Autoriser") }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val p = progress
                        if (p != null) {
                            Text(if (p.total > 0) "Envoi ${p.done}/${p.total}" else p.text, fontWeight = FontWeight.SemiBold)
                            if (p.total > 0) Text(p.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            if (p.fraction >= 0f) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else if (last != null) {
                            Text("Dernière synchronisation : ${formatDate(last.first / 1000)}", fontWeight = FontWeight.SemiBold)
                            Text(last.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Aucune synchronisation effectuée pour l'instant", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { vm.syncNow() }, enabled = p == null) { Text("Synchroniser maintenant") }
                    }
                }
            }

            // ---------------- Destination ----------------
            Section("DESTINATION")
            ListItem(
                headlineContent = { Text("Disque") },
                supportingContent = { Text(cfg.diskName.ifEmpty { "Choisir un disque" }) },
                leadingContent = { Icon(Icons.Filled.Storage, null) },
                modifier = Modifier.clickable { vm.reloadDisks(); dlg = "disk" },
            )
            ListItem(
                headlineContent = { Text("Dossier sur le disque") },
                supportingContent = { Text(cfg.remoteBase) },
                leadingContent = { Image(painterResource(R.drawable.fi_sync), null, Modifier.size(28.dp)) },
                modifier = Modifier.clickable { dlg = "base" },
            )

            // ---------------- Contenu ----------------
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("TYPES DE FICHIERS À SYNCHRONISER")
            SyncCategory.entries.filter { it != SyncCategory.OTHER }.forEach { c ->
                ListItem(
                    headlineContent = { Text(c.label) },
                    supportingContent = { Text(c.hint) },
                    leadingContent = {
                        Checkbox(checked = c in cats, onCheckedChange = { vm.syncUpdate { categories = if (c in categories) categories - c else categories + c } })
                    },
                    modifier = Modifier.clickable { vm.syncUpdate { categories = if (c in categories) categories - c else categories + c } },
                )
            }
            ListItem(
                headlineContent = { Text("Autres extensions") },
                supportingContent = { Text(cfg.customExts.ifBlank { "Aucune — saisissez par ex. « apk, kdbx » ou « * » pour tout" }) },
                leadingContent = { Icon(Icons.Filled.Description, null) },
                modifier = Modifier.clickable { dlg = "exts" },
            )

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("D'OÙ VIENNENT LES FICHIERS")
            SwitchRow(
                "Galerie de l'appareil", "Photos, vidéos et audio de tout le téléphone (appareil photo, captures, téléchargements…)",
                cfg.useMediaStore, Icons.Filled.PhotoLibrary,
            ) { on -> vm.syncUpdate { useMediaStore = on } }
            cfg.folders.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.label) },
                    supportingContent = { Text("Dossier et sous-dossiers", fontSize = 12.sp) },
                    leadingContent = { Icon(Icons.Filled.Folder, null) },
                    trailingContent = { IconButton(onClick = { vm.syncRemoveFolder(f) }) { Icon(Icons.Filled.Delete, "Retirer") } },
                )
            }
            ListItem(
                headlineContent = { Text("Ajouter un dossier…") },
                supportingContent = { Text("Nécessaire pour les documents et archives (Documents, Téléchargements, WhatsApp…)") },
                leadingContent = { Icon(Icons.Filled.CreateNewFolder, null) },
                modifier = Modifier.clickable { folderLauncher.launch(null) },
            )
            val needsFolder = cfg.folders.isEmpty() &&
                (SyncCategory.DOCUMENTS in cats || SyncCategory.ARCHIVES in cats || cfg.customExtSet.isNotEmpty())
            if (needsFolder) Text(
                "Les documents, archives et extensions personnalisées sont cherchés dans les dossiers ajoutés ci-dessus.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ---------------- Planification ----------------
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("PLANIFICATION")
            ListItem(
                headlineContent = { Text("Fréquence") },
                supportingContent = { Text("Toutes les ${intervalLabel(cfg.intervalMin)} (Android peut décaler légèrement)") },
                leadingContent = { Icon(Icons.Filled.Schedule, null) },
                modifier = Modifier.clickable { dlg = "interval" },
            )
            SwitchRow("Wi-Fi uniquement", "Évite d'utiliser vos données mobiles", cfg.wifiOnly, Icons.Filled.Wifi) { on -> vm.syncUpdate { wifiOnly = on } }
            SwitchRow("Seulement en charge", "Économise la batterie", cfg.chargingOnly, Icons.Filled.BatteryChargingFull) { on -> vm.syncUpdate { chargingOnly = on } }
            SwitchRow(
                "Retrouver le serveur automatiquement", "Si son adresse change, le chercher par son nom sur le réseau local",
                cfg.autoDiscover, Icons.Filled.Search,
            ) { on -> vm.syncUpdate { autoDiscover = on } }

            // ---------------- Outils & journal ----------------
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("JOURNAL")
            if (log.isEmpty()) Text("Rien pour l'instant.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            else Column(Modifier.padding(horizontal = 16.dp)) {
                log.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = { dlg = "reset" }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Réinitialiser la mémoire de synchronisation")
            }
            Text(
                "Rien n'est jamais supprimé : effacer une photo du téléphone ne l'efface pas du cloud. " +
                    "Le trafic n'est pas chiffré (HTTP) : utilisez un réseau de confiance, un VPN ou une adresse https://.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    when (dlg) {
        "enable" -> EnableSyncDialog(vm.user?.name ?: "", onDismiss = { dlg = "" }) { pass -> dlg = ""; vm.syncEnable(pass) }
        "disk" -> AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Disque de destination") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val writable = vm.disks.filter { it.rw }
                    if (writable.isEmpty()) Text("Aucun disque accessible en écriture.")
                    writable.forEach { d ->
                        ListItem(
                            headlineContent = { Text(if (d.personal) "Mon espace" else d.name) },
                            supportingContent = { Text("${humanSize(d.used)} utilisés sur ${humanSize(d.size)}") },
                            leadingContent = { RadioButton(selected = cfg.diskId == d.id, onClick = null) },
                            modifier = Modifier.clickable { dlg = ""; vm.syncUpdate { diskId = d.id; diskName = if (d.personal) "Mon espace" else d.name } },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Fermer") } },
        )
        "base" -> TextDialog("Dossier sur le disque", "Chemin (ex. /HomeCloud Sync/Pixel)", cfg.remoteBase, "Enregistrer", { dlg = "" }) { v ->
            dlg = ""; vm.syncUpdate { remoteBase = v }
        }
        "exts" -> ExtensionsDialog(cfg.customExts, onDismiss = { dlg = "" }) { v -> dlg = ""; vm.syncUpdate { customExts = v } }
        "interval" -> AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text("Fréquence de synchronisation") },
            text = {
                Column {
                    intervals.forEach { (min, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = { RadioButton(selected = cfg.intervalMin == min, onClick = null) },
                            modifier = Modifier.clickable { dlg = ""; vm.syncUpdate { intervalMin = min } },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dlg = "" }) { Text("Fermer") } },
        )
        "reset" -> ConfirmDialog(
            "Réinitialiser la mémoire ?",
            "Au prochain passage, HomeCloud Sync revérifie chaque fichier. Ceux déjà présents sur le cloud avec la même taille ne sont pas renvoyés.",
            "Réinitialiser", onDismiss = { dlg = "" },
        ) { dlg = ""; vm.syncResetMemory() }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String, subtitle: String, checked: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, fontSize = 12.sp) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun EnableSyncDialog(userName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activer HomeCloud Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pour se reconnecter seul en arrière-plan, HomeCloud Sync a besoin du mot de passe de « $userName ». " +
                        "Il est chiffré dans le coffre de clés d'Android, reste sur cet appareil et n'est utilisé que pour la synchronisation.",
                    fontSize = 14.sp,
                )
                PasswordField(pass, { pass = it }, "Mot de passe")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = pass.isNotEmpty()) { Text("Activer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ExtensionsDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Autres extensions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Extensions séparées par des virgules, cherchées dans les dossiers ajoutés. « * » envoie tous les fichiers.", fontSize = 14.sp)
                OutlinedTextField(v, { v = it }, label = { Text("ex. apk, kdbx, gpx") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(v.trim()) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
