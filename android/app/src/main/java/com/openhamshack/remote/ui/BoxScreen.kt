package com.openhamshack.remote.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.videolan.libvlc.util.VLCVideoLayout
import com.openhamshack.remote.AppViewModel
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.Link
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Receiver
import com.openhamshack.remote.api.Status
import com.openhamshack.remote.player.PlayPhase
import com.openhamshack.remote.player.PlayerState
import com.openhamshack.remote.player.RxPlayer

/** One box's screen. [onBack] leaves it: to the boxes, or to the controller's apps for QO-100 WB on a controller. */
@Composable
fun BoxScreen(s: BoxSession, vm: AppViewModel, onBack: () -> Unit = { vm.closeBox() }) {
    val status by s.status.collectAsStateWithLifecycle()
    val link by s.link.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Tabs: 0 Receive (terrestrial tuning), 1 QO-100, 2 Camera. The picture
    // plays on the first two; the camera page stops it (no speaker into the
    // microphone). /api/qo100 is polled only while its page shows: that
    // poll holds the box's connection to BATC open.
    LaunchedEffect(tab) {
        s.setReceiveShown(tab != TAB_CAMERA)
        s.setQo100Shown(tab == TAB_QO100)
    }
    LaunchedEffect(s) {
        s.messages.collect { snackbar.showSnackbar(it) }
    }
    KeepScreenOn()

    if (fullscreen && tab == 0) {
        FullscreenReceive(s, status, onExit = { fullscreen = false })
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(s, status, link, onBack = onBack, onPin = { pinDialog = true })
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Side by side on a tablet or a phone on its side: the picture
                // is what the operator watches, the controls sit beside it.
                val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                val sideWidth = if (maxWidth > 1000.dp) 400.dp else 340.dp
                val main: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.wb_tab_receive)) })
                            Tab(tab == TAB_QO100, { tab = TAB_QO100 }, text = { Text("QO-100") }, modifier = Modifier.testTag("qo100Tab"))
                            // A controller does not publish the app's stream input.
                            if (s.platform == null) {
                                Tab(tab == TAB_CAMERA, { tab = TAB_CAMERA }, text = { Text(stringResource(R.string.wb_tab_camera)) }, modifier = Modifier.testTag("cameraTab"))
                            }
                        }
                        when (tab) {
                            0 -> ReceivePicture(s, status, onFullscreen = { fullscreen = true })
                            TAB_QO100 -> {
                                // Narrow: the picture above the transponder, so a
                                // tune is seen to lock without scrolling.
                                if (!wide) ReceivePicture(s, status, onFullscreen = { fullscreen = true; tab = 0 })
                                Qo100Page(s, status, Modifier.fillMaxWidth())
                            }
                            else -> CameraPreview(s)
                        }
                    }
                }
                val side: @Composable () -> Unit = {
                    when (tab) {
                        0 -> {
                            TransmitPanel(s, status, link)
                            TxSettingsPanel(s, status)
                            TunePanel(s, status)
                        }
                        TAB_QO100 -> if (wide) Qo100Side(s, status, link) else {
                            TransmitPanel(s, status, link)
                            TxSettingsPanel(s, status)
                        }
                        else -> {
                            TransmitPanel(s, status, link)
                            CameraControls(s, status)
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        // The QO-100 page is taller than a screen: it scrolls
                        // on its own, beside the picture and transmit.
                        main(Modifier.weight(1f).fillMaxSize()
                            .then(if (tab == TAB_QO100) Modifier.verticalScroll(rememberScrollState()) else Modifier))
                        Column(
                            Modifier.width(sideWidth).fillMaxSize()
                                .verticalScroll(rememberScrollState()).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { side() }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        main(Modifier.fillMaxWidth())
                        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { side() }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
    if (pinDialog) PinDialog(s, onDismiss = { pinDialog = false })
}

private const val TAB_QO100 = 1
private const val TAB_CAMERA = 2

@Composable
private fun TopBar(s: BoxSession, status: Status?, link: Link, onBack: () -> Unit, onPin: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val p = s.platform
            TextButton(onClick = onBack) {
                Text(stringResource(if (p != null) R.string.wb_platform_back else R.string.signin_back_to_boxes))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(status?.config?.callsign?.ifBlank { null },
                        if (p != null) stringResource(R.string.shack_engine_title, p.appName, p.controllerName) else s.box.title,
                    ).distinct().joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                )
                Text(
                    when (link) {
                        Link.Connecting -> stringResource(R.string.link_connecting, s.box.address.display)
                        Link.Up -> stringResource(R.string.link_up, status?.version ?: "", s.box.address.display)
                        is Link.Down -> stringResource(R.string.link_down, link.why)
                    },
                    color = if (link is Link.Down) Amber else Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
            val auth = status?.auth
            when {
                auth == null -> {}
                // The controller signed this device in; the level is what it may do.
                auth.platform -> Text(
                    stringResource(R.string.wb_platform_signed_in, auth.user, stringResource(levelRes(auth.level))),
                    color = Dim, modifier = Modifier.padding(8.dp).testTag("level"),
                )
                auth.local -> Text(stringResource(R.string.signin_box_screen), color = Dim, modifier = Modifier.padding(8.dp))
                auth.authorised -> {
                    // Sign out ends the session at the box too; Forget PIN only here.
                    TextButton(onClick = { s.signOut() }, modifier = Modifier.testTag("signOut")) { Text(stringResource(R.string.signin_sign_out)) }
                    TextButton(onClick = { s.forgetPin() }) { Text(stringResource(R.string.signin_forget_pin), color = Dim) }
                }
                auth.pinSet -> Button(onClick = onPin, modifier = Modifier.testTag("enterPin")) { Text(stringResource(R.string.signin_enter_pin)) }
            }
        }
        if (status?.auth?.viewOnly == true) {
            // The console's yellow bar for a browser without the PIN; on a
            // controller, the level.
            Text(
                if (status.auth.platform) s.viewOnlyReason(status)
                else stringResource(if (status.auth.pinSet) R.string.signin_view_only_pin_set else R.string.signin_view_only_no_pin),
                color = Color.Black, modifier = Modifier.fillMaxWidth().background(Amber).padding(8.dp),
            )
        }
    }
}

@Composable
private fun PinDialog(s: BoxSession, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.signin_hint), color = Dim)
                OutlinedTextField(
                    pin, { pin = it; error = null }, singleLine = true, isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.testTag("pin"),
                )
                error?.let { Text(it, color = OnAirRed) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && pin.isNotEmpty(), onClick = {
                busy = true
                s.login(pin) { err -> busy = false; if (err == null) onDismiss() else error = err }
            }, modifier = Modifier.testTag("signIn")) { Text(stringResource(R.string.signin_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ReceivePicture(s: BoxSession, status: Status?, onFullscreen: () -> Unit) {
    val player by s.player.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        VideoSurface(s)
        PictureOverlay(status, player, compact = false)
        TextButton(onClick = onFullscreen, modifier = Modifier.align(Alignment.BottomEnd)) { Text(stringResource(R.string.wb_full_screen)) }
    }
}

@Composable
internal fun VideoSurface(s: BoxSession) = VideoSurface(s.player)

/** The libVLC picture of [player], for any kind that plays a transport stream. */
@Composable
internal fun VideoSurface(player: RxPlayer) {
    AndroidView(
        factory = { ctx -> VLCVideoLayout(ctx).also { player.attach(it) } },
        onRelease = { player.detach(it) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** A level word's resource: View, Control, Transmit. */
@androidx.annotation.StringRes
internal fun levelRes(level: String): Int = when (level) {
    "transmit" -> R.string.level_transmit
    "control" -> R.string.level_control
    else -> R.string.level_view
}

@Composable
private fun FullscreenReceive(s: BoxSession, status: Status?, onExit: () -> Unit) {
    val player by s.player.state.collectAsStateWithLifecycle()
    val ptt by s.ptt.state.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    Immersive()
    androidx.activity.compose.BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { overlay = !overlay }) {
        VideoSurface(s)
        if (overlay) {
            PictureOverlay(status, player, compact = false)
            TextButton(onClick = onExit, modifier = Modifier.align(Alignment.BottomEnd)) { Text(stringResource(R.string.wb_exit_full_screen)) }
        }
        // On air is never hidden, and the way off air is always one tap.
        if (status?.onAir == true || ptt.phase != com.openhamshack.remote.ptt.PttPhase.Idle) {
            Button(
                onClick = { s.ptt.unkey() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = OnAirRed),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            ) { Text(stringResource(R.string.wb_on_air_tap_to_stop), fontWeight = FontWeight.Bold) }
        }
    }
}

/** Frequency, symbol rate, mode, lock and MER over the picture, as the box's own TV label does. */
@Composable
fun PictureOverlay(status: Status?, player: PlayerState, compact: Boolean) {
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        val rx = status?.receivers?.firstOrNull { it.heard } ?: status?.rx
        if (rx != null) {
            Column(Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(6.dp)) {
                Text(receiverTuningText(rx, status), color = Color.White, fontSize = 14.sp)
                Text(lockText(rx), color = when (rx.locked) { true -> LockGreen; false -> Amber; null -> Dim }, fontSize = 14.sp)
                val prog = programmeLine(rx)
                if (prog.isNotBlank() && !compact) Text(prog, color = Color.White, fontSize = 13.sp)
            }
        }
        if (status?.onAir == true) {
            Text(
                status.onAirSeconds?.let { stringResource(R.string.wb_on_air_clock, clock(it)) } ?: stringResource(R.string.shack_on_air),
                color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).background(OnAirRed).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        val p = playerLine(player, rx)
        if (p.isNotBlank()) {
            Text(p, color = Amber, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(4.dp))
        }
    }
}

/** The player's state in words, for the bottom of the picture (blank while all is well). */
@Composable
internal fun playerLine(player: PlayerState, rx: Receiver?): String {
    val line = when (player.phase) {
        PlayPhase.Connecting -> stringResource(R.string.wb_player_connecting)
        PlayPhase.Reconnecting -> stringResource(R.string.wb_player_reconnecting, player.lastProblem ?: stringResource(R.string.wb_player_no_picture_short))
        PlayPhase.Stopped -> ""
        // No picture is normal with no signal: the box keeps the stream
        // open on null packets and the picture comes with a lock.
        PlayPhase.Playing -> if (player.hasVideo) "" else if (rx?.locked == true) stringResource(R.string.wb_player_keyframe)
            else stringResource(R.string.wb_player_nothing)
    }
    val n = player.reconnects
    return when {
        n <= 0 -> line
        line.isEmpty() -> pluralStringResource(R.plurals.wb_player_reconnects, n, n)
        else -> pluralStringResource(R.plurals.wb_player_line_reconnects, n, n, line)
    }
}

/** The heard receiver's tuning, naming it when the box has more than one. */
@Composable
internal fun receiverTuningText(rx: Receiver, status: Status?): String =
    if ((status?.receivers?.size ?: 1) > 1) stringResource(R.string.wb_tuning_receiver, tuningText(rx), rx.id) else tuningText(rx)

@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
internal fun Immersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
