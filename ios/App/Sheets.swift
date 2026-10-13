import OpenHamShackKit
import SwiftUI

/// The remote PIN, for control. Five wrong in a minute lock remote sign-in
/// on the box for that minute; the box's own words say so.
struct PINSheet: View {
    let session: BoxSession
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var busy = false
    @State private var problem: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("PIN", text: $pin)
                        .keyboardType(.numberPad)
                        .focused($focused)
                        .onSubmit { Task { await go() } }
                } footer: {
                    // A whole sentence per device rather than Device.This in
                    // the middle of one: other languages inflect "this" by
                    // the noun, and put it elsewhere in the sentence.
                    if Device.isPhone {
                        Text("The remote PIN set on the box's touchscreen (Settings › Security). This iPhone stays signed in to \(session.box.name) until you sign out or the PIN is cleared on the box.")
                    } else {
                        Text("The remote PIN set on the box's touchscreen (Settings › Security). This iPad stays signed in to \(session.box.name) until you sign out or the PIN is cleared on the box.")
                    }
                }
                if let problem { Section { Text(problem).foregroundStyle(.red) } }
            }
            .navigationTitle("Control \(session.box.name)")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else { Button("Sign in") { Task { await go() } }.disabled(pin.isEmpty) }
                }
            }
            .onAppear { focused = true }
        }
    }

    private func go() async {
        busy = true
        problem = await session.signIn(pin: pin)
        busy = false
        if problem == nil { dismiss() }
    }
}

struct SettingsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section {
                    Stepper(value: $model.settings.cachingMs, in: 100...2000, step: 50) {
                        HStack {
                            Text("Picture buffer")
                            Spacer()
                            Text("\(model.settings.cachingMs) ms").monospacedDigit().foregroundStyle(.secondary)
                        }
                    }
                    Toggle("Hardware decoding", isOn: $model.settings.hardwareDecoding)
                } header: { Text("Received picture") } footer: {
                    Text("A smaller buffer keeps the picture closer to the box's own (200-300 ms suits a good Wi-Fi link); a larger one rides out a poor link. Software decoding copes better with some badly damaged pictures but uses more battery.")
                }
                Section {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    LabeledContent("Remote id", value: model.store.remoteID)
                } header: { Text("This app") } footer: {
                    Text("The remote id is how a box knows \(Device.this) holds its transmitter (shown on the box's console while keyed from here).")
                }
                Section {
                    Text("OpenHamShack Remote is free software under the GNU GPL, version 3. It uses VLCKit (LGPL-2.1, VideoLAN), HaishinKit (BSD-3-Clause) and libsrt (MPL-2.0, Haivision).")
                        .font(.footnote)
                } header: { Text("Licences") }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
