import OpenHamShackKit
import SwiftUI

/// The boxes this iPad knows, the ones advertising on the network, and a
/// way to add one by address.
struct BoxListView: View {
    @Environment(AppModel.self) private var model
    @State private var browser = BoxBrowser()
    @State private var adding = false
    @State private var prefill: String = ""
    @State private var resolving: String?
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.boxes.isEmpty {
                        Text("No boxes yet. Add one by its address: it is on the box's own screen under Settings › Network.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.boxes) { box in
                        Button { model.open(box) } label: {
                            HStack {
                                Image(systemName: "antenna.radiowaves.left.and.right")
                                VStack(alignment: .leading) {
                                    Text(box.name).font(.headline)
                                    Text(box.address.display).font(.subheadline).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if model.store.session(for: box.address) != nil {
                                    Label("PIN", systemImage: "key.fill").labelStyle(.iconOnly).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .swipeActions {
                            Button("Forget", role: .destructive) { model.forget(box) }
                        }
                    }
                } header: { Text("Your boxes") }

                Section {
                    if browser.found.isEmpty {
                        // The browser's problem is the system's own sentence; the fallback is ours.
                        Text(browser.problem ?? String(localized: "Looking… A box on this Wi-Fi shows here by its callsign (OpenHamShack 0.9.17 and later). Over a tailnet, add it by address."))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    ForEach(browser.found) { f in
                        Button {
                            Task { await use(f) }
                        } label: {
                            HStack {
                                Image(systemName: "dot.radiowaves.left.and.right")
                                VStack(alignment: .leading) {
                                    Text(f.name).font(.headline)
                                    Text([String(localized: "OpenHamShack \(f.version ?? "")"), f.port.map { String(localized: "port \($0)") }]
                                        .compactMap { $0 }.joined(separator: " · "))
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if resolving == f.name { ProgressView() }
                            }
                        }
                    }
                } header: { Text("On this network") }

                if let problem {
                    Section { Text(problem).foregroundStyle(.red) }
                }
            }
            .navigationTitle("OpenHamShack Remote")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { prefill = ""; adding = true } label: { Label("Add a box", systemImage: "plus") }
                }
            }
            .sheet(isPresented: $adding) {
                AddBoxSheet(initialAddress: prefill)
            }
            .onAppear { browser.start() }
            .onDisappear { browser.stop() }
        }
    }

    private func use(_ f: BoxBrowser.Found) async {
        resolving = f.name
        defer { resolving = nil }
        do {
            // Watching needs no PIN, so a found box opens straight away; the
            // PIN is asked for from the box screen when wanted.
            let addr = try await BoxBrowser.resolve(f)
            model.open(model.store.remember(name: f.name, address: addr))
        } catch {
            problem = (error as? LocalizedError)?.errorDescription ?? "\(error)"
        }
    }
}

/// Add a box: its address, a name, and the remote PIN if the operator has
/// it (without one the box can be watched but not controlled).
struct AddBoxSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var initialAddress: String
    @State private var address = ""
    @State private var name = ""
    @State private var pin = ""
    @State private var busy = false
    @State private var problem: String?
    @State private var reachedFailed = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("198.51.100.40, openhamshack.local or 100.x.y.z", text: $address)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Name (optional)", text: $name)
                } header: { Text("Box") } footer: {
                    Text("The box's address is on its own screen, Settings › Network. Port 8080 unless you add another, e.g. 198.51.100.40:8081.")
                }
                Section {
                    SecureField("Remote PIN (optional)", text: $pin)
                        .keyboardType(.numberPad)
                } header: { Text("Control") } footer: {
                    Text("Without the PIN you can watch and see the box's state, but not tune or transmit. The PIN is set on the box's touchscreen, Settings › Security.")
                }
                if let problem {
                    Section { Text(problem).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Add a box")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        // Two literals in a ternary are a String, which Button shows as it is;
                        // each is looked up in the catalog first.
                        Button(reachedFailed ? String(localized: "Add anyway") : String(localized: "Connect")) { Task { await connect() } }
                            .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            .onAppear { if address.isEmpty { address = initialAddress } }
            .onChange(of: address) { reachedFailed = false }
        }
    }

    private func connect() async {
        problem = nil
        let addr: BoxAddress
        do { addr = try BoxAddress.parse(address) } catch {
            problem = (error as? LocalizedError)?.errorDescription
            return
        }
        busy = true
        defer { busy = false }
        let client = OpenHamShackClient(address: addr)
        var callsign = ""
        if !reachedFailed {
            do {
                let st = try await client.status()
                callsign = st.config.callsign
            } catch {
                let why = (error as? LocalizedError)?.errorDescription ?? "\(error)"
                problem = String(localized: "Could not reach an OpenHamShack at \(addr.display): \(why)")
                reachedFailed = true
                return
            }
        }
        if !pin.isEmpty {
            do {
                let token = try await client.login(pin: pin)
                model.store.setSession(token, for: addr)
            } catch {
                let why = (error as? LocalizedError)?.errorDescription ?? "\(error)"
                problem = String(localized: "PIN not accepted: \(why)")
                return
            }
        }
        // Saved as the box's name, so it stays in the language it was added in.
        let display = !name.isEmpty ? name : !callsign.isEmpty ? String(localized: "\(callsign)'s OpenHamShack") : addr.display
        let box = model.store.remember(name: display, address: addr)
        model.reloadBoxes()
        dismiss()
        model.open(box)
    }
}
