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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    var showPassword by remember { mutableStateOf(false) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setAvatar(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compte et paramètres") },
                navigationIcon = { IconButton(onClick = { vm.goMain() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AvatarView(vm.avatar, vm.user?.name ?: "?", 88.dp)
                Text(vm.user?.name ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    (if (vm.isAdmin) "Administrateur · " else "") + vm.serverDisplay,
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            SectionTitle("COMPTE")
            ListItem(
                headlineContent = { Text("Changer la photo de profil") },
                leadingContent = { Icon(Icons.Filled.Image, null) },
                modifier = Modifier.clickable { pickAvatar.launch("image/*") },
            )
            ListItem(
                headlineContent = { Text("Changer le mot de passe") },
                leadingContent = { Icon(Icons.Filled.Key, null) },
                modifier = Modifier.clickable { showPassword = true },
            )
            ListItem(
                headlineContent = { Text("HomeCloud Sync") },
                supportingContent = {
                    Text(
                        if (vm.syncCfg.enabled) "Activé · photos, vidéos et documents envoyés automatiquement"
                        else "Synchroniser photos, vidéos et documents avec votre cloud",
                    )
                },
                leadingContent = { Icon(Icons.Filled.Sync, null) },
                modifier = Modifier.clickable { vm.openSync() },
            )
            if (vm.isAdmin) ListItem(
                headlineContent = { Text("Administration") },
                supportingContent = { Text("Utilisateurs, disques et autorisations") },
                leadingContent = { Icon(Icons.Filled.AdminPanelSettings, null) },
                modifier = Modifier.clickable { vm.openAdmin() },
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle("APPARENCE")
            listOf("system" to "Automatique (suit le système)", "light" to "Clair", "dark" to "Sombre").forEach { (mode, label) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = vm.themeMode == mode, onClick = { vm.setTheme(mode) }) },
                    colors = ListItemDefaults.colors(),
                    modifier = Modifier.clickable { vm.setTheme(mode) },
                )
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text("Se déconnecter", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { vm.logout() },
            )
        }
    }

    if (showPassword) PasswordChangeDialog(onDismiss = { showPassword = false }) { old, new ->
        showPassword = false
        vm.changePassword(old, new)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PasswordChangeDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val err = when {
        new.isNotEmpty() && new.length < 8 -> "8 caractères minimum"
        again.isNotEmpty() && new != again -> "Les mots de passe diffèrent"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changer le mot de passe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(old, { old = it }, "Ancien mot de passe")
                PasswordField(new, { new = it }, "Nouveau mot de passe")
                PasswordField(again, { again = it }, "Confirmer")
                if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(old, new) }, enabled = old.isNotEmpty() && new.length >= 8 && new == again) { Text("Modifier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
