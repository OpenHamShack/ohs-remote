package com.openhamshack.remote.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import com.openhamshack.core.tv.LocalTvInert
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.tv.Choice
import com.openhamshack.core.tv.ChoiceOverlay
import com.openhamshack.core.tv.FaceKeys
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.core.tv.rememberRemoteKeys
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.remote.AppViewModel
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.Link
import com.openhamshack.remote.R
import com.openhamshack.remote.data.DiscoveredBox
import com.openhamshack.remote.data.PlayerSettings
import com.openhamshack.remote.data.SavedBox

/**
 * A television: Android TV, Google TV, Fire TV (a stick or a set with Fire
 * OS built in). The UI mode is what those report; a device with no
 * touchscreen at all is treated the same, because a phone layout driven
 * by a D-pad is unusable whatever the device calls itself (some Fire OS
 * sets and boxes have been seen reporting NORMAL).
 */
fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(UiModeManager::class.java)
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

/** The TV's safe area: ~5% overscan at 1080p, inside which every TV screen lays itself out. */
internal val TvSafeH = 48.dp
internal val TvSafeV = 27.dp

/**
 * Scroll a focused item into view only as far as needed, as on a phone,
 * instead of the leanback pivot Compose uses by default on a TV (focused
 * item held ~30% down the list). The pivot is meant for long rails; on
 * these short screens it scrolled headings away for nothing and left the
 * box picker's content in the lower half of a Fire TV's screen.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object ScrollOnlyAsNeeded : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f        // already in view
            offset < 0 && trailing > containerSize -> 0f          // larger than the view: leave it
            offset < 0 || size > containerSize -> offset          // above: bring its top in
            else -> trailing - containerSize                      // below: bring its bottom in
        }
    }
}

/**
 * [ScrollOnlyAsNeeded] that brings an item in [margin] px clear of the
 * view's edges, for a scrolling row of [TvItem]s: flush with the edge, a
 * focused item's ring (3 dp, and the item lifted 2% by its scale) is cut
 * off by the scroll's clip. The content needs that much room past its last
 * item (end padding inside the scroll), or there is nowhere to scroll to.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class ScrollWithMargin(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        ScrollOnlyAsNeeded.calculateScrollDistance(offset - margin, size + 2 * margin, containerSize)
}

/** Focus on a TV is a ring the whole room can see, not Material's faint tint. */
internal val TvFocus = Color(0xFF4FC3F7)
private val TvCard = Color(0xFF161616)
private val TvCardFocused = Color(0xFF243440)

/**
 * OpenHamShack on a TV, receive only, as the Apple TV target (ios/TV): pick a
 * box, watch its picture full screen, OK for the status overlay, up/down
 * through receivers and receive presets.
 *
 * No transmit and no camera, by design: a TV across the room is no place
 * to hold a transmitter's dead-man, and has no camera. The PIN is asked
 * for only because changing channel changes the box; watching needs none.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvUi(vm: AppViewModel) {
    OpenHamShackTheme {
        val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        // Back is routed to the BackHandlers by hand. Compose spends a Back
        // press that nothing consumes on clearing focus (seen on the Android
        // TV emulator): the first Back on any focused row only took the
        // focus ring away, the overlay stayed up, and the next key went
        // nowhere. Taken here, as the key bubbles up from whatever is
        // focused, it reaches the innermost enabled BackHandler (tune panel,
        // then overlay, then the boxes) and, with none, the activity's own
        // Back, which leaves the app as a TV user expects.
        Surface(
            Modifier.fillMaxSize().onKeyEvent { e ->
                if (e.key != Key.Back) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) back?.onBackPressed()
                true
            },
            color = Color.Black,
        ) {
            val session by vm.session.collectAsStateWithLifecycle()
            val shack by vm.shack.screen.collectAsStateWithLifecycle()
            val s = session
            CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyAsNeeded) {
                // A controller (OpenHamShack) has its own screens; a Classic box the picture.
                if (shack != null) TvShack(vm) else if (s == null) TvBoxPicker(vm) else TvWatch(s, vm)
            }
        }
    }
}

// --- choosing a box -------------------------------------------------------

/**
 * Boxes found by Bonjour first - one press of OK and the picture is up, no
 * typing on a remote - then the saved ones, then an address typed with the
 * on-screen keyboard for a box Bonjour cannot reach (over the tailnet, or
 * one not yet advertising). NsdManager is the platform's, so this works on
 * a Fire TV with no Google services.
 */
@Composable
private fun TvBoxPicker(vm: AppViewModel) {
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    val found by vm.discovery.boxes.collectAsStateWithLifecycle()
    val findError by vm.discovery.error.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<String?>(null) }   // the address to start from, "" for a new one
    var settings by remember { mutableStateOf(false) }

    val firstFound = remember { FocusRequester() }
    val firstSaved = remember { FocusRequester() }
    val addItem = remember { FocusRequester() }
    // Something must hold focus from the start, or the first press of the
    // remote is spent finding the screen. Then, once, the first box found
    // on the network takes it: Bonjour answers a moment after the list is
    // drawn, and that box is almost always the one wanted.
    var movedToFound by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { if (boxes.isNotEmpty()) firstSaved.requestFocus() else addItem.requestFocus() }
    }
    LaunchedEffect(found.firstOrNull()?.serviceName) {
        if (found.isNotEmpty() && !movedToFound) {
            delay(100)
            if (runCatching { firstFound.requestFocus() }.isSuccess) movedToFound = true
        }
    }

    // A fixed header and two columns that start at the top, inside the
    // TV's safe area. It was one centred LazyColumn: on a TV, Compose's
    // focus scrolling keeps the focused item about a third of the way down
    // (the leanback "pivot", the platform default when FEATURE_LEANBACK is
    // present), so the list scrolled under the focus - the title went off
    // the top on the emulator, and on Brian's Fire TV (Fire OS 7, a 1080p
    // UI on a 4K panel) the content sat in the lower half below a large
    // empty band. The rows were also of three different widths. TvUi now
    // gives every TV screen the plain bring-into-view (scroll only as far
    // as needed), and here nothing scrolls unless the boxes outgrow the
    // screen.
    Column(Modifier.fillMaxSize().padding(horizontal = TvSafeH, vertical = TvSafeV)) {
        Text(stringResource(R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tv_receive_only),
            color = Dim, fontSize = 15.sp, maxLines = 1)
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { TvSection(stringResource(R.string.boxes_on_this_network)) }
                if (found.isEmpty()) {
                    item {
                        TvNote(findError ?: stringResource(R.string.tv_looking))
                    }
                }
                items(found, key = { "nsd-" + it.serviceName }) { d ->
                    val saved = boxes.firstOrNull { it.address == d.address }
                    FoundRow(d, saved, if (d == found.first()) firstFound else null) { vm.openDiscovered(d) }
                }
                item { TvSection(stringResource(R.string.tv_your_boxes)) }
                if (boxes.isEmpty()) item { TvNote(stringResource(R.string.tv_none_yet)) }
                items(boxes, key = { it.id }) { box ->
                    SavedRow(
                        box, if (box == boxes.first()) firstSaved else null,
                        onWatch = { vm.open(box) }, onPin = { adding = box.address.display }, onForget = { vm.removeBox(box.id) },
                    )
                }
            }
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvSection(stringResource(R.string.boxes_add))
                TvItem(onClick = { adding = "" }, focus = addItem, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(stringResource(R.string.tv_type_address), fontSize = 20.sp)
                        Text(stringResource(R.string.tv_address_where),
                            color = Dim, fontSize = 14.sp)
                    }
                }
                TvSection(stringResource(R.string.tv_this_tv))
                TvItem(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tv_picture_settings), fontSize = 18.sp) }
            }
        }
    }

    adding?.let { start -> AddBoxDialog(vm, start, onDismiss = { adding = null }) }
    if (settings) {
        val current by vm.playerSettings.collectAsStateWithLifecycle()
        PictureSettingsDialog(current, onSave = { vm.setPlayerSettings(it) }, onDismiss = { settings = false })
    }
}

@Composable
internal fun TvSection(title: String) {
    Text(title, color = Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun TvNote(text: String) {
    Text(text, color = Dim, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
}

/**
 * One focusable thing on a TV screen. Focus shows as a bright ring, a
 * lighter card and a slight lift - visible from a sofa, which Material's
 * default focus tint is not. OK (centre) and Enter click it: Compose's
 * clickable handles both keys.
 */
@Composable
internal fun TvItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    /** A chip that is the current choice: green edge, so it reads apart from the focus ring. */
    selected: Boolean = false,
    /** A chip in a row of many (modes, bookmarks): less padding, the same ring. */
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    // The family TV kit's flag (core-candidates TvKit, GUIDE 5): true under an
    // open overlay, so this screen's items stop taking focus as the kit's do.
    val inert = LocalTvInert.current
    Row(
        modifier
            .focusProperties { if (inert) canFocus = false }
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .background(if (focused) TvCardFocused else TvCard)
            .border(3.dp, if (focused) TvFocus else if (selected) LockGreen else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 22.dp, vertical = if (compact) 5.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun FoundRow(d: DiscoveredBox, saved: SavedBox?, focus: FocusRequester?, onOpen: () -> Unit) {
    TvItem(onClick = onOpen, focus = focus, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 22.sp)
            Text(
                listOfNotNull(d.address.display, d.version.ifBlank { null }?.let { stringResource(R.string.box_version, it) },
                    if (saved?.session != null) stringResource(R.string.box_pin_entered) else null).joinToString(" · "),
                color = Dim, fontSize = 14.sp,
            )
        }
        Text(stringResource(R.string.tv_watch), color = TvFocus, fontSize = 16.sp)
    }
}

/** Watch, PIN and Forget side by side: left/right moves between them, up/down between boxes. */
@Composable
private fun SavedRow(box: SavedBox, focus: FocusRequester?, onWatch: () -> Unit, onPin: () -> Unit, onForget: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvItem(onClick = onWatch, focus = focus, modifier = Modifier.weight(1f)) {
            Column {
                Text(box.title, fontSize = 22.sp)
                val access = stringResource(if (box.session != null) R.string.box_pin_entered else R.string.box_view_only)
                Text(box.address.display + " · " + access,
                    color = Dim, fontSize = 14.sp)
            }
        }
        TvItem(onClick = onPin) { Text(stringResource(if (box.session != null) R.string.tv_new_pin else R.string.tv_pin), fontSize = 16.sp) }
        // Two presses, like the console's destructive buttons.
        TvItem(onClick = { if (armed) onForget() else armed = true }) {
            Text(stringResource(if (armed) R.string.tv_forget_confirm else R.string.tv_forget), color = if (armed) OnAirRed else Dim, fontSize = 16.sp)
        }
    }
}

/**
 * Address and an optional PIN, typed with the TV's on-screen keyboard. The
 * address field takes focus as the dialog opens, which brings the keyboard
 * up; its Next goes to the PIN and the PIN's Done adds - so the whole thing
 * can be done without leaving the keyboard. Nothing is saved until a box
 * answers (AppViewModel.addAndWatch).
 */
@Composable
private fun AddBoxDialog(vm: AppViewModel, start: String, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(start) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val pinField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { if (start.isEmpty()) first.requestFocus() else pinField.requestFocus() }
    }
    val go = {
        if (!busy) {
            busy = true
            error = null
            vm.addAndWatch(address, pin) { why ->
                busy = false
                if (why == null) onDismiss() else error = why
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (start.isEmpty()) stringResource(R.string.boxes_add_title) else stringResource(R.string.tv_pin_for, start)) },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    address, { address = it; error = null }, label = { Text(stringResource(R.string.tv_address_label)) },
                    singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { pinField.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(first),
                )
                OutlinedTextField(
                    pin, { pin = it; error = null },
                    label = { Text(stringResource(R.string.tv_pin_label)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(pinField),
                )
                Text(stringResource(R.string.tv_pin_hint),
                    color = Dim, fontSize = 14.sp)
                error?.let { Text(it, color = OnAirRed) }
            }
        },
        confirmButton = {
            TvItem(onClick = go) { Text(stringResource(if (busy) R.string.common_connecting else R.string.tv_add_and_watch), fontSize = 16.sp) }
        },
        dismissButton = { TvItem(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), fontSize = 16.sp) } },
    )
}

/**
 * The tablet's two picture settings, with buttons instead of a slider and a
 * switch: both are awkward on a D-pad. Hardware decoding matters most here:
 * a TV's decoder is whatever its chip maker shipped, and some stop at the
 * damaged frames a marginal signal brings.
 */
@Composable
private fun PictureSettingsDialog(current: PlayerSettings, onSave: (PlayerSettings) -> Unit, onDismiss: () -> Unit) {
    var caching by remember { mutableIntStateOf(current.cachingMs) }
    var hw by remember { mutableStateOf(current.hwDecode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_picture_settings)) },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.tv_buffer, caching), Modifier.weight(1f), fontSize = 18.sp)
                    TvItem(onClick = { caching = (caching - 50).coerceAtLeast(100) }) { Text(stringResource(R.string.tv_less), fontSize = 16.sp) }
                    TvItem(onClick = { caching = (caching + 50).coerceAtMost(1500) }) { Text(stringResource(R.string.tv_more), fontSize = 16.sp) }
                }
                Text(stringResource(R.string.tv_buffer_hint),
                    color = Dim, fontSize = 14.sp)
                TvItem(onClick = { hw = !hw }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_hardware_decoding), Modifier.weight(1f), fontSize = 18.sp)
                    Text(stringResource(if (hw) R.string.tv_on else R.string.tv_off), color = if (hw) LockGreen else Amber, fontSize = 18.sp)
                }
                Text(stringResource(R.string.tv_hardware_hint),
                    color = Dim, fontSize = 14.sp)
            }
        },
        confirmButton = { TvItem(onClick = { onSave(PlayerSettings(caching, hw)); onDismiss() }) { Text(stringResource(R.string.common_save), fontSize = 16.sp) } },
        dismissButton = { TvItem(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), fontSize = 16.sp) } },
    )
}

// --- watching ---------------------------------------------------------------

/**
 * The picture, full screen, on the remote's standard map for a video face
 * (core keys/, REMOTE-KEYS.md §4): Left/Right and the channel keys step
 * through the channels (tvChannels), one per press; Up lists them to
 * choose from; OK shows or hides the status overlay, with its Tune button
 * focused (OK again opens the tune panel), and Down shows it too; Menu or
 * OK held opens the Tune panel; Play/Pause mutes the picture's sound; Menu
 * held or Info shows the key legend. Back hides the overlay, or with it
 * hidden goes back to the boxes - what a TV user expects Back to do.
 * Until 12/10/2026 Up/Down changed channel and Play/Pause toggled the
 * overlay (Brian's decisions 2 and 9).
 */
@Composable
internal fun TvWatch(s: BoxSession, vm: AppViewModel, onBack: () -> Unit = { vm.closeBox() }) {
    val status by s.status.collectAsStateWithLifecycle()
    val link by s.link.collectAsStateWithLifecycle()
    val player by s.player.state.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    var tuning by remember { mutableStateOf(false) }
    var qo100 by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    // Where the channel keys have got to. Not read back from the box after each
    // press: the box's answer lags a quick run of presses, which would
    // otherwise all step from the same place.
    var index by remember { mutableIntStateOf(-2) }
    val rootFocus = remember { FocusRequester() }
    val tuneButton = remember { FocusRequester() }

    KeepScreenOn()
    Immersive()
    val resources = LocalResources.current
    fun step(by: Int) {
        val st = status ?: return
        val list = tvChannels(st.receivers, !st.auth.viewOnly)
        if (list.isEmpty()) {
            banner = if (st.auth.viewOnly && st.auth.platform) s.viewOnlyReason(st)
                else resources.getString(if (st.auth.viewOnly) viewOnlyLine(st.auth.pinSet) else R.string.tv_no_other_channels)
            return
        }
        val heard = st.receivers.firstOrNull { it.heard } ?: st.rx
        index = tvStep(list.size, if (index in list.indices) index else tvChannelIndex(list, heard), by)
        when (val ch = list[index]) {
            is TvChannel.Hear -> s.hear(ch.id)
            is TvChannel.Tune -> s.tune(heard.id, frequencyMHz = ch.preset.mhz, offsetMHz = 0.0)
        }
        banner = channelTitle(resources, list[index])
    }
    fun canStep(): Boolean = status?.let { tvChannels(it.receivers, !it.auth.viewOnly).isNotEmpty() } == true
    val face = object : FaceKeys {
        override val faceClass = FaceClass.VIDEO
        override fun can(a: RemoteAction): Boolean = when (a) {
            RemoteAction.CHANNEL_PREV, RemoteAction.CHANNEL_NEXT, RemoteAction.CONTROLS, RemoteAction.FOCUS_DOWN,
            RemoteAction.FREQUENCY, RemoteAction.SETTINGS, RemoteAction.MUTE -> true
            RemoteAction.CHOOSE -> canStep()
            // No bands on QO-100 WB; transmit from a TV is not built here yet (REMOTE-KEYS.md "Decided").
            else -> false
        }
        override fun perform(f: Fired) {
            when (f.action) {
                RemoteAction.CHANNEL_PREV -> step(-1)
                RemoteAction.CHANNEL_NEXT -> step(+1)
                RemoteAction.CHOOSE -> choosing = true
                RemoteAction.CONTROLS -> overlay = !overlay
                RemoteAction.FOCUS_DOWN -> { overlay = true; runCatching { tuneButton.requestFocus() } }
                RemoteAction.FREQUENCY, RemoteAction.SETTINGS -> tuning = true
                RemoteAction.MUTE -> s.player.mute(!s.player.state.value.muted)
                else -> {}
            }
        }
        @Composable override fun label(a: RemoteAction): String? = when (a) {
            RemoteAction.FOCUS_DOWN -> stringResource(R.string.wb_key_focus_down)
            RemoteAction.CONTROLS -> stringResource(R.string.wb_key_controls)
            RemoteAction.SETTINGS, RemoteAction.FREQUENCY -> stringResource(R.string.wb_key_settings)
            else -> null
        }
    }
    val keys = rememberRemoteKeys(face, overlayOpen = tuning || qo100 || choosing, onBack = onBack)
    BackHandler(enabled = !tuning && !qo100 && overlay) { overlay = false }
    // Focus follows what is on screen: the overlay's Tune button while it
    // shows, else the picture itself, which takes OK and up/down. Without
    // this, hiding the overlay would drop focus and the next key would go
    // nowhere.
    LaunchedEffect(overlay, tuning, qo100, choosing, keys.legend) {
        if (tuning || qo100 || choosing || keys.legend) return@LaunchedEffect
        delay(50)
        runCatching { if (overlay) tuneButton.requestFocus() else rootFocus.requestFocus() }
    }
    // A refusal or a sign-out from the session shows where a channel name would.
    LaunchedEffect(s) { s.messages.collect { banner = it } }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3000)
            banner = null
        }
    }


    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            // The picture is the surface: with focus here every key of the map
            // acts; on the overlay's buttons the arrows move between them and
            // OK presses them (RemoteKeys).
            .onFocusChanged { keys.onSurface = it.isFocused }
            .remoteKeys(keys)
            .focusable(),
    ) {
        VideoSurface(s)
        val rx = status?.receivers?.firstOrNull { it.heard } ?: status?.rx
        if (overlay && !tuning && !qo100) {
            Column(
                Modifier.align(Alignment.TopStart).padding(48.dp)
                    .background(Color(0xB0000000), RoundedCornerShape(16.dp)).padding(28.dp)
                    .widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val p = s.platform
                Text(listOfNotNull(status?.config?.callsign?.ifBlank { null },
                    if (p != null) stringResource(R.string.shack_engine_title, p.appName, p.controllerName) else s.box.title,
                ).distinct().joinToString(" · "), color = Color.White.copy(alpha = 0.8f), fontSize = 20.sp)
                if (rx != null) {
                    Text(receiverTuningText(rx, status),
                        color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(lockText(rx), color = when (rx.locked) { true -> LockGreen; false -> Amber; null -> Dim }, fontSize = 22.sp)
                    programmeLine(rx).ifBlank { null }?.let { Text(it, color = Color.White, fontSize = 20.sp) }
                }
                when (val l = link) {
                    Link.Connecting -> Text(stringResource(R.string.link_connecting, s.box.address.display), color = Dim, fontSize = 18.sp)
                    is Link.Down -> Text(stringResource(R.string.link_down, l.why), color = Amber, fontSize = 18.sp)
                    Link.Up -> {}
                }
                playerLine(player, rx).ifBlank { null }?.let { Text(it, color = Amber, fontSize = 18.sp) }
                if (player.muted) Text(stringResource(R.string.shack_muted), color = Dim, fontSize = 18.sp)
                val st = status
                if (st != null && st.auth.viewOnly) {
                    Text(if (st.auth.platform) s.viewOnlyReason(st) else stringResource(viewOnlyLine(st.auth.pinSet)),
                        color = Amber, fontSize = 16.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvItem(onClick = { tuning = true }, focus = tuneButton) { Text(stringResource(R.string.wb_tv_tune), fontSize = 18.sp) }
                    TvItem(onClick = { qo100 = true }) { Text(stringResource(R.string.wb_tv_qo100), fontSize = 18.sp) }
                    val canStep = st != null && tvChannels(st.receivers, !st.auth.viewOnly).isNotEmpty()
                    // Key hints are phrases joined by "  ·  ", as the TV's others are.
                    val back = stringResource(if (s.platform != null) R.string.wb_platform_tv_back else R.string.wb_tv_back_boxes)
                    Text(
                        if (canStep) stringResource(R.string.wb_tv_hint_channel) + "  ·  " + back else back,
                        color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp,
                    )
                }
            }
        }
        if (tuning) {
            TvTunePanel(s, status, Modifier.align(Alignment.CenterEnd), onClose = { tuning = false },
                onQo100 = { tuning = false; qo100 = true })
        }
        if (qo100) TvQo100(s, status, onClose = { qo100 = false })
        if (choosing) status?.let { st ->
            // Up: the channels as a grid, the one being heard marked; OK tunes there.
            val list = tvChannels(st.receivers, !st.auth.viewOnly)
            val heard = st.receivers.firstOrNull { it.heard } ?: st.rx
            val at = if (index in list.indices) index else tvChannelIndex(list, heard)
            ChoiceOverlay(stringResource(R.string.wb_tv_channels_title),
                listOf(list.mapIndexed { i, ch -> Choice(i.toString(), channelTitle(resources, ch), selected = i == at) }),
                onPick = { c -> c.key.toIntOrNull()?.let { step(it - (if (index in list.indices) index else tvChannelIndex(list, heard))) } },
                onClose = { choosing = false })
        }
        // On air is never hidden: the TV cannot stop it, but must not hide it.
        if (status?.onAir == true) {
            Text(
                status?.onAirSeconds?.let { stringResource(R.string.wb_tv_on_air_clock, clock(it)) } ?: stringResource(R.string.wb_tv_on_air),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    .background(OnAirRed, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        banner?.let {
            Text(
                it, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                    .background(Color(0xC0000000), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 18.dp),
            )
        }
        RemoteKeysHost(keys, face)
    }
}

/** Why a TV cannot change channel, as a string resource: with a PIN on the box, or without one. */
@StringRes
internal fun viewOnlyLine(pinSet: Boolean): Int =
    if (pinSet) R.string.tv_view_only_pin_set else R.string.tv_view_only_no_pin
