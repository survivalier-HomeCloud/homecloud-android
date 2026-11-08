package fr.survivalier.homecloud

import android.content.Context

/** Préférences privées à l'application (adresse, identifiant, jeton de session de 12 h, thème). */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("homecloud", Context.MODE_PRIVATE)

    var address: String
        get() = p.getString("address", "") ?: ""
        set(v) { p.edit().putString("address", v).apply() }

    var username: String
        get() = p.getString("username", "") ?: ""
        set(v) { p.edit().putString("username", v).apply() }

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v).apply() }

    /** "system", "light" ou "dark". */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) { p.edit().putString("theme", v).apply() }
}
