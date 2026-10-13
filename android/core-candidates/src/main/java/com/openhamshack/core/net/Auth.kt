package com.openhamshack.core.net

/**
 * What a box lets this client do, in the three words every kind uses.
 *
 * - **View**: see and hear everything, change nothing.
 * - **Control**: tune, change mode, choose a receiver.
 * - **Transmit**: key the transmitter.
 *
 * A receive-only client (the TV kinds) treats Transmit exactly as Control:
 * the level is what the box allows, not what the client does with it.
 */
enum class AuthLevel {
    View, Control, Transmit;

    val canControl: Boolean get() = this >= Control

    companion object {
        /** The box's word, leniently: unknown words read as View, the safe direction. */
        fun parse(word: String?): AuthLevel = when (word?.trim()?.lowercase()) {
            "transmit", "tx" -> Transmit
            "control", "tune" -> Control
            else -> View
        }
    }
}

/**
 * A session cookie as the box set it: name and value, kept per box and sent
 * by hand (`Cookie: name=value`). Never a shared cookie jar - two boxes
 * reached through one host name would share a session - and never the
 * password or PIN itself.
 */
data class SessionCookie(val name: String, val value: String) {
    val header: String get() = "$name=$value"

    /** As stored: name=value. */
    override fun toString() = header

    companion object {
        fun parse(stored: String?): SessionCookie? {
            val s = stored?.trim().orEmpty()
            val eq = s.indexOf('=')
            if (eq <= 0 || eq == s.length - 1) return null
            return SessionCookie(s.substring(0, eq), s.substring(eq + 1))
        }

        /**
         * The session from a response's Set-Cookie headers: the first cookie
         * named in [names], or with [names] empty the first cookie at all.
         * A cookie being cleared (empty value or Max-Age=0) is not a session.
         */
        fun fromSetCookie(headers: List<String>, names: Set<String> = emptySet()): SessionCookie? =
            headers.firstNotNullOfOrNull { h ->
                val parts = h.split(';').map { it.trim() }
                val first = parts.firstOrNull() ?: return@firstNotNullOfOrNull null
                val eq = first.indexOf('=')
                if (eq <= 0) return@firstNotNullOfOrNull null
                val name = first.substring(0, eq)
                val value = first.substring(eq + 1).trim('"')
                val expired = parts.any { it.equals("Max-Age=0", ignoreCase = true) }
                if (value.isEmpty() || expired || (names.isNotEmpty() && name !in names)) null
                else SessionCookie(name, value)
            }
    }
}

/**
 * The family's `auth` block, as every kind reports it (in status, in a login
 * answer, and on StationFlow at the open `GET /api/auth`). OpenHamShack calls the
 * secret a PIN (`pinSet`), StationFlow a password (`passwordSet`); either
 * reads as [secretSet].
 */
data class AuthState(
    val secretSet: Boolean,
    val authorised: Boolean,
    val level: AuthLevel,
    val local: Boolean = false,
) {
    /**
     * What this client may do now. With no secret set the box is open and
     * serves everything - that box's operator chose it - so it is Control
     * (never Transmit, which a box with no secret must not hand out).
     */
    val effective: AuthLevel
        get() = when {
            !secretSet -> AuthLevel.Control
            authorised -> level
            else -> AuthLevel.View
        }

    companion object {
        fun parse(o: org.json.JSONObject): AuthState = AuthState(
            secretSet = o.optBoolean("passwordSet", o.optBoolean("pinSet", true)),
            authorised = o.optBoolean("authorised"),
            level = AuthLevel.parse(if (o.isNull("level")) null else o.optString("level")),
            local = o.optBoolean("local"),
        )
    }
}
