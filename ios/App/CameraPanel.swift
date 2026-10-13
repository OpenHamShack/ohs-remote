import OpenHamShackCamera
import OpenHamShackKit
import SwiftUI

/// This iPad's camera as the box's picture: preview, front/back, and
/// sending it to the box's Stream source over SRT.
///
/// The order is the one REMOTE-API.md gives: make Stream the box's picture
/// source (its listener only runs then), start sending, wait for the box to
/// say it is receiving, then key. "Send to the box" does the first two;
/// the Transmit panel's button does the last, and the box itself refuses a
/// key-up while nothing has arrived.
struct CameraPanel: View {
    let session: BoxSession
    @Environment(AppModel.self) private var model
    @State private var starting = false

    var body: some View {
        let camera = model.camera
        VStack(alignment: .leading, spacing: 16) {
            if !session.canControl { ViewOnlyNote(session: session, what: "send the camera") }
            CameraPreview(sender: camera)
                .aspectRatio(16 / 9, contentMode: .fit)
                .background(Color.black)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .topLeading) { sendingBadge(camera) }
            HStack {
                Picker("Source", selection: Binding(get: { camera.source }, set: { s in Task { await camera.select(s) } })) {
                    ForEach(CameraSender.Source.allCases) { s in
                        if s == .pattern || CameraSender.cameraAvailable { Text(s.words).tag(s) }
                    }
                }
                .pickerStyle(.segmented)
            }
            Toggle("Send sound from the microphone", isOn: Binding(get: { camera.microphoneOn }, set: { camera.microphoneOn = $0 }))
                .disabled(camera.source == .pattern)
            if !CameraSender.cameraAvailable {
                Text("No camera here (the simulator has none): the test pattern stands in.").font(.caption).foregroundStyle(.secondary)
            }
            sendButton(camera)
            phaseLine(camera)
            if let st = session.status {
                if st.config.source == PictureSource.stream.rawValue {
                    StreamStateLine(stream: st.stream)
                } else if camera.phase != .off {
                    Label("The box's picture is \(PictureSource(rawValue: st.config.source)?.words ?? st.config.source), not \(Device.this).",
                          systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.subheadline)
                }
            }
            Text("Sent as Larix would: SRT to the box's Stream source, H.264 1280×720 30 fps about 2.5 Mbit/s with AAC sound. The box re-encodes it for the air. Transmit from the Transmit panel once the box says it is receiving.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.top, 4)
        .task { await camera.startPreview() }
        .onDisappear {
            if camera.phase == .off { Task { await camera.stopPreview() } }
        }
    }

    @ViewBuilder private func sendingBadge(_ camera: CameraSender) -> some View {
        if camera.phase == .sending {
            Text("SENDING · \(camera.kbps) kbit/s").font(.caption.bold()).monospacedDigit()
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Color.red, in: Capsule()).foregroundStyle(.white).padding(8)
        }
    }

    @ViewBuilder private func sendButton(_ camera: CameraSender) -> some View {
        if camera.phase == .off || isFailed(camera.phase) {
            Button {
                Task { await send() }
            } label: {
                Label(starting ? "Starting…" : "Send to the box as its picture", systemImage: "video.fill")
                    .frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!session.canControl || starting)
        } else {
            Button(role: .destructive) {
                camera.stop()
            } label: {
                Label("Stop sending", systemImage: "stop.fill").frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
        }
    }

    private func isFailed(_ p: CameraSender.Phase) -> Bool {
        if case .failed = p { return true }
        return false
    }

    @ViewBuilder private func phaseLine(_ camera: CameraSender) -> some View {
        switch camera.phase {
        case .off: EmptyView()
        case .connecting: Label("Connecting to \(camera.target)…", systemImage: "arrow.triangle.2.circlepath").font(.subheadline)
        case .sending: Label("Sending to \(camera.target)", systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.subheadline)
        case .retrying(let why): Label("\(why). Trying again…", systemImage: "arrow.clockwise").foregroundStyle(.orange).font(.subheadline)
        case .failed(let why): Label(why, systemImage: "xmark.octagon").foregroundStyle(.red).font(.subheadline)
        }
    }

    /// Stream first (the listener runs only then), then the SRT facts, then send.
    private func send() async {
        starting = true
        defer { starting = false }
        do {
            if session.status?.config.source != PictureSource.stream.rawValue {
                session.setSource(.stream)
            }
            let target = try await session.client.streamTarget()
            model.camera.start(address: session.box.address, target: target)
        } catch {
            session.notice = "Could not start the camera: \((error as? LocalizedError)?.errorDescription ?? "\(error)")"
        }
    }
}
