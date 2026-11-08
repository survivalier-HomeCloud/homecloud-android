package fr.survivalier.homecloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions

private fun drawableFor(ext: String): Int? = when (ext) {
    "css" -> R.drawable.fi_css
    "docx", "doc" -> R.drawable.fi_docx
    "html", "htm" -> R.drawable.fi_html
    "js" -> R.drawable.fi_js
    "mp3", "wav", "ogg", "flac", "m4a" -> R.drawable.fi_mp3
    "mp4", "mkv", "avi", "mov", "webm" -> R.drawable.fi_mp4
    "odb" -> R.drawable.fi_odb
    "odf" -> R.drawable.fi_odf
    "odg" -> R.drawable.fi_odg
    "odp" -> R.drawable.fi_odp
    "ods" -> R.drawable.fi_ods
    "odt" -> R.drawable.fi_odt
    "pdf" -> R.drawable.fi_pdf
    "php" -> R.drawable.fi_php
    "png", "jpg", "jpeg", "gif", "webp" -> R.drawable.fi_png
    "pptx", "ppt" -> R.drawable.fi_pptx
    "py" -> R.drawable.fi_py
    "xlsx", "xls" -> R.drawable.fi_xlsx
    else -> null
}

fun iconRes(name: String, dir: Boolean, sync: Boolean = false): Int {
    if (dir) return if (sync) R.drawable.fi_sync else R.drawable.fi_folder
    val ext = name.substringAfterLast('.', "").lowercase()
    return drawableFor(ext) ?: R.drawable.fi_file
}

@Composable
fun FileIcon(entry: Entry, size: Dp = 40.dp, sync: Boolean = false) {
    Box(Modifier.size(size)) {
        Image(painterResource(iconRes(entry.name, entry.dir, sync)), null, Modifier.fillMaxSize())
        if (entry.locked) {
            Image(painterResource(R.drawable.fi_lock), null, Modifier.size(size * 0.5f).align(Alignment.BottomEnd))
        }
    }
}

@Composable
fun DiskIcon(personal: Boolean, size: Dp = 32.dp) {
    Image(painterResource(if (personal) R.drawable.fi_diskhome else R.drawable.fi_disk), null, Modifier.size(size))
}

/** Photo de profil ronde, ou initiales sur fond bleu. */
@Composable
fun AvatarView(bitmap: ImageBitmap?, name: String, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(name.take(2).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
        }
    }
}

@Composable
fun TextDialog(
    title: String, label: String, initial: String = "", confirm: String = "OK",
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(v) }, enabled = v.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

/** Mot de passe d'un fichier verrouillé / à déverrouiller. */
@Composable
fun LockPromptDialog(p: LockPrompt) {
    var pass by remember(p) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = p.onCancel,
        title = { Text(p.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("« ${p.fileName} » est protégé par un mot de passe.")
                PasswordField(pass, { pass = it }, "Mot de passe du fichier")
                p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { p.onSubmit(pass) }, enabled = pass.isNotEmpty()) { Text(p.confirmLabel) } },
        dismissButton = { TextButton(onClick = p.onCancel) { Text("Annuler") } },
    )
}

/** Choix du mot de passe d'un fichier (4 caractères minimum, confirmation). */
@Composable
fun SetLockDialog(fileName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val err = when {
        p1.isNotEmpty() && p1.length < 4 -> "4 caractères minimum"
        p2.isNotEmpty() && p1 != p2 -> "Les mots de passe diffèrent"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verrouiller « $fileName »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ce mot de passe sera demandé pour ouvrir, télécharger, modifier, renommer ou supprimer le fichier. Seul l'administrateur peut s'en passer.", fontSize = 13.sp)
                PasswordField(p1, { p1 = it }, "Mot de passe")
                PasswordField(p2, { p2 = it }, "Confirmer")
                if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(p1) }, enabled = p1.length >= 4 && p1 == p2) { Text("Verrouiller") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, danger: Boolean = false,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun VSpace(h: Dp) { Spacer(Modifier.height(h)) }
