package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openhamshack.remote.AppViewModel
import com.openhamshack.remote.BuildConfig
import com.openhamshack.remote.R
import com.openhamshack.remote.data.DiscoveredBox
import com.openhamshack.remote.data.PlayerSettings
import com.openhamshack.remote.data.SavedBox
import kotlin.math.roundToInt

@Composable
fun OpenHamShackRemoteUi(vm: AppViewModel) {
    OpenHamShackTheme {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            val session by vm.session.collectAsStateWithLifecycle()
            val shack by vm.shack.screen.collectAsStateWithLifecycle()
            val s = session
            if (shack != null) {
                ShackPhone(vm)
            } else if (s == null) {
                BoxListScreen(vm)
            } else {
                BackHandler { vm.closeBox() }
                BoxScreen(s, vm)
            }
        }
    }
}

@Composable
private fun BoxListScreen(vm: AppViewModel) {
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { settings = true }) { Text(stringResource(R.string.boxes_settings)) }
        }
        val found by vm.discovery.boxes.collectAsStateWithLifecycle()
        val findError by vm.discovery.error.collectAsStateWithLifecycle()
        if (boxes.isEmpty() && found.isEmpty()) {
            Text(stringResource(R.string.boxes_empty_hint), color = Dim)
        }
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (boxes.isNotEmpty()) item { Text(stringResource(R.string.boxes_saved), color = Dim, style = MaterialTheme.typography.labelLarge) }
            items(boxes, key = { it.id }) { box ->
                BoxRow(box, found.any { it.address == box.address }, { vm.open(box) }, { vm.removeBox(box.id) })
            }
            item {
                Text(stringResource(R.string.boxes_on_this_network), color = Dim, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp))
            }
            val unsaved = found.filter { d -> boxes.none { it.address == d.address } }
            items(unsaved, key = { "nsd-" + it.serviceName }) { d -> FoundRow(d) { vm.openDiscovered(d) } }
            item {
                Text(
                    findError ?: stringResource(
                        if (unsaved.isEmpty()) {
                            if (found.isEmpty()) R.string.boxes_looking_wifi else R.string.boxes_all_saved
                        } else R.string.boxes_found_hint,
                    ),
                    color = Dim, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.testTag("addBox")) { Text(stringResource(R.string.boxes_add)) }
    }
    if (adding) AddBoxDialog(onAdd = { name, addr -> vm.addBox(name, addr) }, onDismiss = { adding = false })
    if (settings) {
        val current by vm.playerSettings.collectAsStateWithLifecycle()
        val id by vm.clientId.collectAsStateWithLifecycle()
        SettingsDialog(current, id, onSave = { vm.setPlayerSettings(it) }, onDismiss = { settings = false })
    }
}

@Composable
private fun FoundRow(d: DiscoveredBox, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("found")) {
        Column(Modifier.padding(16.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium)
            val version = d.version.ifBlank { null }?.let { stringResource(R.string.box_version, it) }
            Text(listOfNotNull(d.address.display, version).joinToString(" · "),
                color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BoxRow(box: SavedBox, here: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(box.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        box.address.display,
                        if (box.session != null) stringResource(R.string.box_pin_entered) else null,
                        if (here) stringResource(R.string.box_on_this_network) else null,
                    ).joinToString(" · "),
                    color = Dim, style = MaterialTheme.typography.bodySmall,
                )
            }
            // Two taps, like the console's destructive buttons.
            TextButton(onClick = { if (armed) onRemove() else armed = true }) {
                Text(stringResource(if (armed) R.string.boxes_remove_confirm else R.string.boxes_remove), color = if (armed) OnAirRed else Dim)
            }
        }
    }
}

@Composable
private fun AddBoxDialog(onAdd: (String, String) -> String?, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.boxes_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address, { address = it; error = null }, label = { Text(stringResource(R.string.boxes_address_label)) },
                    singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.testTag("address"),
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.boxes_name_label)) }, singleLine = true)
                error?.let { Text(it, color = OnAirRed) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val why = onAdd(name, address)
                if (why == null) onDismiss() else error = why
            }, modifier = Modifier.testTag("confirmAdd")) { Text(stringResource(R.string.boxes_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SettingsDialog(current: PlayerSettings, clientId: String, onSave: (PlayerSettings) -> Unit, onDismiss: () -> Unit) {
    var caching by remember { mutableFloatStateOf(current.cachingMs.toFloat()) }
    var hw by remember { mutableStateOf(current.hwDecode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_buffer, caching.roundToInt()))
                Slider(caching, { caching = (it / 50).roundToInt() * 50f }, valueRange = 100f..1500f)
                Text(
                    stringResource(R.string.settings_buffer_hint),
                    color = Dim, style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_hardware_decoding), Modifier.weight(1f))
                    Switch(hw, { hw = it })
                }
                Text(
                    stringResource(R.string.settings_hardware_hint),
                    color = Dim, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    stringResource(R.string.settings_about, clientId, BuildConfig.VERSION_NAME),
                    color = Dim, style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(PlayerSettings(caching.roundToInt(), hw)); onDismiss() }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
