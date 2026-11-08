package fr.survivalier.homecloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var showCreateUser by remember { mutableStateOf(false) }
    var showCreateDisk by remember { mutableStateOf(false) }
    var deleteUser by remember { mutableStateOf<AdminUser?>(null) }
    var deleteDisk by remember { mutableStateOf<Disk?>(null) }

    val sharedDisks = vm.disks.filter { !it.personal }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administration") },
                navigationIcon = { IconButton(onClick = { vm.goMain() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (tab == 0) showCreateUser = true else showCreateDisk = true }) {
                Icon(Icons.Filled.Add, if (tab == 0) "Nouvel utilisateur" else "Nouveau disque")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Utilisateurs") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Disques partagés") })
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                if (tab == 0) {
                    items(vm.adminUsers, key = { it.name }) { u ->
                        ListItem(
                            headlineContent = { Text(u.name) },
                            supportingContent = {
                                Text((if (u.admin) "Administrateur · " else "") + "Espace personnel : ${humanSize(u.quota)}", fontSize = 12.sp)
                            },
                            trailingContent = {
                                if (u.name != vm.user?.name) IconButton(onClick = { deleteUser = u }) {
                                    Icon(Icons.Filled.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                    }
                } else {
                    if (sharedDisks.isEmpty()) item {
                        Text("Aucun disque partagé.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(sharedDisks, key = { it.id }) { d ->
                        ListItem(
                            leadingContent = { DiskIcon(false) },
                            headlineContent = { Text(d.name) },
                            supportingContent = { Text("${humanSize(d.used)} utilisés sur ${humanSize(d.size)}", fontSize = 12.sp) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { vm.openPerms(d) }) { Icon(Icons.Filled.Security, "Autorisations") }
                                    IconButton(onClick = { deleteDisk = d }) {
                                        Icon(Icons.Filled.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreateUser) CreateUserDialog(
        onDismiss = { showCreateUser = false },
        onConfirm = { n, p, q, a -> showCreateUser = false; vm.createUser(n, p, q, a) },
    )
    if (showCreateDisk) CreateDiskDialog(
        onDismiss = { showCreateDisk = false },
        onConfirm = { n, s -> showCreateDisk = false; vm.createDisk(n, s) },
    )
    deleteUser?.let { u ->
        ConfirmDialog(
            "Supprimer l'utilisateur", "Supprimer « ${u.name} » et son espace personnel chiffré ? Cette action est irréversible.",
            "Supprimer", danger = true, onDismiss = { deleteUser = null }, onConfirm = { deleteUser = null; vm.deleteUser(u.name) },
        )
    }
    deleteDisk?.let { d ->
        ConfirmDialog(
            "Supprimer le disque", "Supprimer « ${d.name} » et tous ses fichiers ? Cette action est irréversible.",
            "Supprimer", danger = true, onDismiss = { deleteDisk = null }, onConfirm = { deleteDisk = null; vm.deleteDisk(d) },
        )
    }
    vm.permsFor?.let { d -> PermsDialog(vm, d) }
}

@Composable
private fun CreateUserDialog(onDismiss: () -> Unit, onConfirm: (String, String, Int, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var quota by remember { mutableStateOf("5") }
    var admin by remember { mutableStateOf(false) }
    val q = quota.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvel utilisateur") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Identifiant") }, singleLine = true)
                PasswordField(pass, { pass = it }, "Mot de passe (8 minimum)")
                OutlinedTextField(
                    quota, { quota = it.filter(Char::isDigit) }, label = { Text("Espace personnel (Go, 5 minimum)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Administrateur", Modifier.weight(1f))
                    Switch(admin, { admin = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, pass, q ?: 5, admin) },
                enabled = name.isNotBlank() && pass.length >= 8 && q != null && q >= 5,
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun CreateDiskDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("50") }
    val s = size.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau disque partagé") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom du disque") }, singleLine = true)
                OutlinedTextField(
                    size, { size = it.filter(Char::isDigit) }, label = { Text("Taille (Go, 1 à 100000)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Le disque est chiffré (LUKS2). L'espace n'est consommé qu'à l'usage.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, s ?: 1) }, enabled = name.isNotBlank() && s != null && s in 1..100000) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private fun permLabel(p: String) = when (p) { "rw" -> "Lecture / écriture"; "r" -> "Lecture seule"; else -> "Aucun accès" }

@Composable
private fun PermsDialog(vm: MainViewModel, d: Disk) {
    AlertDialog(
        onDismissRequest = { vm.closePerms() },
        title = { Text("Autorisations : ${d.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val users = vm.adminUsers.filter { !it.admin }
                if (users.isEmpty()) Text("Aucun utilisateur standard.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("Les administrateurs ont toujours accès à tous les disques.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                users.forEach { u ->
                    var open by remember { mutableStateOf(false) }
                    val cur = vm.permMap[u.name] ?: "none"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(u.name, Modifier.weight(1f))
                        Column {
                            OutlinedButton(onClick = { open = true }) { Text(permLabel(cur), fontSize = 12.sp) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                listOf("none", "r", "rw").forEach { p ->
                                    DropdownMenuItem(text = { Text(permLabel(p)) }, onClick = { open = false; vm.setPerm(u.name, p) })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closePerms() }) { Text("Fermer") } },
    )
}
