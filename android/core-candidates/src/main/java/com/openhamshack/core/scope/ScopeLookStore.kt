package com.openhamshack.core.scope

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Each radio's [ScopeLook] on this device, kept across restarts. Keyed by
 * whatever names the radio to the kind - the Icom kind uses its box and the
 * radio's model, Flex its box and the radio's model - so a TV that watches
 * two radios keeps a look for each, and a phone and a TV each keep their
 * own: a palette that reads well across a room may not on a phone.
 */
class ScopeLookStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scope_look", Context.MODE_PRIVATE)

    fun load(key: String): ScopeLook = ScopeLook.decode(prefs.getString(key, null))

    fun save(key: String, look: ScopeLook) { prefs.edit().putString(key, look.encode()).apply() }
}

/**
 * The look for [key] as Compose state: read once, and written back on every
 * change through the returned setter. A new key (another radio) reads again.
 */
@Composable
fun rememberScopeLook(key: String): Pair<ScopeLook, (ScopeLook) -> Unit> {
    val context = LocalContext.current
    val store = remember { ScopeLookStore(context) }
    val state: MutableState<ScopeLook> = remember(key) { mutableStateOf(store.load(key)) }
    return state.value to { look: ScopeLook -> state.value = look; store.save(key, look) }
}
