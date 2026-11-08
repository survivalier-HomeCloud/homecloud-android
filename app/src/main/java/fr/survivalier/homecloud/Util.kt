package fr.survivalier.homecloud

import android.webkit.MimeTypeMap
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun humanSize(b: Long): String {
    val units = arrayOf("o", "Ko", "Mo", "Go", "To")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
    return if (i == 0) "$b o" else String.format(Locale.getDefault(), "%.1f %s", v, units[i])
}

fun formatDate(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))

fun joinPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

fun parentPath(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

private val textExt = setOf(
    "txt", "md", "py", "php", "js", "css", "c", "h", "cpp", "hpp", "cs", "java", "kt", "sh", "json", "xml",
    "yml", "yaml", "ini", "conf", "cfg", "log", "csv", "sql", "lua", "toml", "asm", "s", "bat", "cmake", "qss",
)

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext in textExt) return "text/plain"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private val badNameChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

fun safeFileName(name: String): String = name.replace(badNameChars, "_").ifBlank { "fichier" }

/** « 192.168.1.20 », « 192.168.1.20:8650 », « https://cloud.exemple.fr » → Server. */
fun parseAddress(input: String): Server? {
    var s = input.trim()
    if (s.isEmpty()) return null
    var https = false
    if (s.startsWith("https://", ignoreCase = true)) {
        https = true
        s = s.substring(8)
    } else if (s.startsWith("http://", ignoreCase = true)) {
        s = s.substring(7)
    }
    s = s.substringBefore('/')
    val host = s.substringBefore(':')
    if (host.isEmpty()) return null
    val port = if (s.contains(':')) (s.substringAfter(':').toIntOrNull() ?: return null) else if (https) 443 else 8650
    if (port !in 1..65535) return null
    return Server(host, port, https)
}
