package com.openhamshack.remote.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.launch
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Status

private val CAMERA_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

@Composable
fun CameraPreview(s: BoxSession) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(CAMERA_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
    }
    val cam by s.camera.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The camera page is landscape: StreamPack encodes in the display's
    // orientation, and a phone held upright would send 720x1280, which the
    // box can only pillarbox into a 16:9 transmission.
    val activity = context as? Activity
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (before != null) activity.requestedOrientation = before }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!granted) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.wb_cam_permission), color = Dim)
                Button(onClick = { ask.launch(CAMERA_PERMISSIONS) }, modifier = Modifier.testTag("allowCamera")) {
                    Text(stringResource(R.string.wb_cam_allow))
                }
            }
        } else {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { v -> scope.launch { s.camera.attachPreview(v) } } },
                onRelease = { v -> s.camera.detachPreview(v) },
                modifier = Modifier.fillMaxSize(),
            )
            if (cam.sending) {
                Text(
                    stringResource(if (cam.streaming) R.string.wb_cam_sending else R.string.wb_cam_connecting),
                    color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(if (cam.streaming) OnAirRed else Color(0x99000000)).padding(6.dp),
                )
            }
        }
    }
}

/**
 * The phone as the box's camera: send over SRT to the Stream source, and
 * make the Stream source the box's picture. Keying is the Transmit panel's
 * job, as for any other source.
 */
@Composable
fun CameraControls(s: BoxSession, status: Status?) {
    val cam by s.camera.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf(s.box.passphrase ?: "") }
    // Said from a coroutine, where stringResource cannot be called.
    val notAnswered = stringResource(R.string.wb_cam_not_answered)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wb_cam_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.wb_cam_how, s.box.address.host + (status?.stream?.port?.let { ":$it" } ?: "")),
                color = Dim, style = MaterialTheme.typography.bodySmall,
            )
            val cfg = status?.config
            val st = status?.stream
            if (cfg != null) {
                Text(stringResource(R.string.wb_cam_box_picture, sourceText(cfg.source)))
                if (cfg.source != "stream") {
                    OutlinedButton(onClick = { s.useStreamSource() }, modifier = Modifier.testTag("useStream")) {
                        Text(stringResource(R.string.wb_cam_use_stream))
                    }
                }
                if (cfg.streamProtocol != "srt") {
                    Text(stringResource(R.string.wb_cam_not_srt, cfg.streamProtocol.uppercase()), color = Amber)
                    OutlinedButton(onClick = { s.switchStreamToSrt() }) { Text(stringResource(R.string.wb_cam_switch_srt)) }
                }
            }
            if (st != null && cfg?.source == "stream") {
                val line = when (st.state) {
                    "receiving" -> {
                        // What arrives (the box's own codec words: data) and the rate.
                        val what = listOfNotNull(st.video, st.audio,
                            st.kbps?.let { stringResource(R.string.wb_kbps, it.toInt()) }).joinToString(" · ")
                        stringResource(when (st.onAir) {
                            "stream" -> R.string.wb_cam_receiving_on_air
                            "card" -> R.string.wb_cam_receiving_card
                            else -> R.string.wb_cam_receiving
                        }, what)
                    }
                    "listening" -> stringResource(R.string.wb_cam_listening, st.port)
                    "error" -> stringResource(R.string.wb_cam_listener, st.message)
                    else -> stringResource(R.string.wb_cam_listener, st.state)
                }
                Text(line, color = if (st.state == "receiving") LockGreen else Dim)
                if (st.note.isNotBlank()) Text(st.note, color = Amber)
                if (st.problem.isNotBlank()) Text(st.problem, color = Dim, style = MaterialTheme.typography.bodySmall)
            }
            // The passphrase comes from the box with the PIN; without it, type it.
            if (st?.secretSet == true && status.auth.viewOnly) {
                OutlinedTextField(
                    pass, { pass = it; s.savePassphrase(it) }, label = { Text(stringResource(R.string.wb_cam_passphrase)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cam.hasFront && cam.hasBack) {
                    OutlinedButton(onClick = { scope.launch { s.camera.switchCamera() } }) {
                        Text(stringResource(if (cam.front) R.string.wb_cam_use_back else R.string.wb_cam_use_front))
                    }
                }
                if (cam.sending) {
                    Button(onClick = { scope.launch { s.camera.stopSending() } },
                        colors = ButtonDefaults.buttonColors(containerColor = OnAirRed)) { Text(stringResource(R.string.wb_cam_stop)) }
                } else {
                    Button(enabled = cam.ready, onClick = {
                        scope.launch {
                            val t = s.srtTarget()
                            if (t == null) s.say(notAnswered)
                            else s.camera.startSending(t)
                        }
                    }, modifier = Modifier.testTag("send")) { Text(stringResource(R.string.wb_cam_send)) }
                }
            }
            cam.error?.let { Text(it, color = Amber) }
        }
    }
}
