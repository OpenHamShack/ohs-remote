package com.openhamshack.kind.stationflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.core.net.SocketState
import com.openhamshack.core.tv.KeepScreenOn
import com.openhamshack.core.tv.OpenHamShackTvTheme
import com.openhamshack.core.tv.LocalTvInert
import com.openhamshack.core.tv.OnAirRed
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.radio.FaceFocus
import com.openhamshack.kind.stationflow.radio.FaceHost
import com.openhamshack.kind.stationflow.radio.RadioFaceTouch
import com.openhamshack.kind.stationflow.radio.faceFor

private enum class TouchPanel { None, Tune, Heard, Sstv }

/**
 * Phones and tablets: the connected radio's own screen, by touch, receive
 * only - the layout OpenHamShack Remote's Icom kind will use when the client is
 * split into a core and kinds (it lives here so it drops in then). A radio
 * with no face of its own gets the TV screen, which works by touch too.
 *
 * The panels (tune, decodes, SSTV) are the TV's, which are touch-friendly
 * already; transmit is not here and cannot be added from here - the client
 * underneath refuses every transmit path (StationFlowClient).
 */
@Composable
fun StationFlowTouchScreen(session: StationFlowSession, onExit: () -> Unit, onSignIn: (() -> Unit)? = null) {
    val state by session.state.collectAsStateWithLifecycle()
    val face = faceFor(state?.modelKey)
    if (face == null && state != null) {
        StationFlowTvScreen(session, onExit, onSignIn)
        return
    }
    OpenHamShackTvTheme {
        LifecycleStartEffect(session) {
            session.foreground(true)
            onStopOrDispose { session.foreground(false) }
        }
        KeepScreenOn()
        val pending by session.pendingHz.collectAsStateWithLifecycle()
        val chosenStep by session.step.collectAsStateWithLifecycle()
        val link by session.link.collectAsStateWithLifecycle()
        val muted by session.muted.collectAsStateWithLifecycle()
        var panel by remember { mutableStateOf(TouchPanel.None) }
        var banner by remember { mutableStateOf<String?>(null) }
        val resources = LocalResources.current
        LaunchedEffect(session) { session.messages.collect { banner = it.text(resources) } }
        LaunchedEffect(banner) { if (banner != null) { delay(3500); banner = null } }
        BackHandler(enabled = panel == TouchPanel.None) { onExit() }
        val st = state
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                CompositionLocalProvider(LocalTvInert provides (panel != TouchPanel.None)) {
                    if (face != null) {
                        RadioFaceTouch(
                            session, face, st, pending, chosenStep ?: session.effectiveStep(), muted,
                            linkProblem = (link as? SocketState.Down)?.let { stringResource(R.string.link_not_answering, it.why) },
                            host = object : FaceHost {
                                override fun openTune() { panel = TouchPanel.Tune }
                                override fun openDecodes() { panel = TouchPanel.Heard }
                                override fun openSstv() { panel = TouchPanel.Sstv }
                                override fun focusChanged(what: FaceFocus) {}
                                // The face keeps its own overlays (RadioFaceTv); nothing for this screen to open.
                                override fun open(panel: com.openhamshack.kind.stationflow.radio.FacePanel) {}
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(stringResource(R.string.link_connecting), color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
                when (panel) {
                    TouchPanel.Tune -> TunePanel(session, st, onClose = { panel = TouchPanel.None }, onSignIn = onSignIn,
                        modifier = Modifier.align(Alignment.CenterEnd))
                    TouchPanel.Heard -> HeardScreen(session, onClose = { panel = TouchPanel.None })
                    TouchPanel.Sstv -> SstvScreen(session, true, {}, onClose = { panel = TouchPanel.None })
                    TouchPanel.None -> {}
                }
                if (st?.transmitting == true) {
                    Text(
                        stringResource(R.string.radio_on_air), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
                            .background(OnAirRed, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                banner?.let {
                    Text(
                        it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).widthIn(max = 600.dp)
                            .background(Color(0xE0101010), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
