package fr.survivalier.homecloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(vm: MainViewModel) {
    var address by remember { mutableStateOf(vm.savedAddress) }
    var username by remember { mutableStateOf(vm.savedUsername) }
    var password by remember { mutableStateOf("") }

    // Premier serveur détecté → pré-rempli si rien n'est saisi
    LaunchedEffect(vm.found.size) {
        if (address.isBlank()) vm.found.firstOrNull()?.let { address = it.display }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(96.dp))
        Text("Home Cloud", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Votre cloud personnel", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SERVEUR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (vm.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.startScan() }) { Icon(Icons.Filled.Refresh, "Rechercher les serveurs") }
                }
                vm.found.forEach { s ->
                    Card(
                        Modifier.fillMaxWidth().clickable { address = s.display },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.name.ifBlank { "Home Cloud" }, fontWeight = FontWeight.SemiBold)
                            Text(s.display, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (vm.found.isEmpty() && !vm.scanning) {
                    Text("Aucun serveur détecté. Saisissez son adresse (ex. 192.168.1.20 ou 192.168.1.20:8650).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Adresse du serveur") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Identifiant") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!vm.loginBusy) vm.login(address, username, password) }),
                )
                vm.loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { vm.login(address, username, password) },
                    enabled = !vm.loginBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.loginBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Se connecter")
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Thème :", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                vm.setTheme(when (vm.themeMode) { "system" -> "light"; "light" -> "dark"; else -> "system" })
            }) {
                Text(when (vm.themeMode) { "light" -> "Clair"; "dark" -> "Sombre"; else -> "Automatique" })
            }
        }
        Text(
            "Connexion non chiffrée (HTTP) : à utiliser sur un réseau de confiance, un VPN ou derrière un proxy HTTPS (https://…).",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
