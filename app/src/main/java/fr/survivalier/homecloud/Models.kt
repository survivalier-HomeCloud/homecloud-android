package fr.survivalier.homecloud

data class Server(val host: String, val port: Int = 8650, val https: Boolean = false, val name: String = "") {
    /** Texte tel que saisi dans le champ « adresse du serveur ». */
    val display: String
        get() = when {
            https -> "https://$host" + if (port == 443) "" else ":$port"
            port == 8650 -> host
            else -> "$host:$port"
        }
}

data class User(val name: String, val admin: Boolean, val personal: String)

data class Disk(
    val id: String,
    val name: String,
    val size: Long,
    val used: Long,
    val personal: Boolean,
    val owner: String,
    val rw: Boolean,
)

data class Entry(val name: String, val dir: Boolean, val size: Long, val mtime: Long, val locked: Boolean)

data class FileInfo(val ctime: Long, val lockOwner: String?)

data class AdminUser(val name: String, val admin: Boolean, val personal: String, val quota: Long)

data class Perm(val user: String, val rw: Boolean)

class ApiException(message: String, val code: Int = 0, val locked: Boolean = false) : Exception(message)
