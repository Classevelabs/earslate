import SwiftUI

/// Where the user supplies their own provider key.
///
/// This screen did not exist. iOS had no way to enter a key at all, because it
/// got its credentials from a broker — so when that broker was removed the app
/// had no path to a working session and no UI that could have created one.
///
/// Two behaviours here are deliberate and match Android:
///
///  - **The key is verified against the provider before it is saved.** A format
///    check can only say a string is shaped like a key. Minting a real session
///    says the key is accepted, the account is in good standing, and the live
///    translation model is reachable on it. Those three things otherwise fail
///    later, mid-conversation, when the user can do nothing about it.
///  - **Format is never a gate.** Only unambiguous mistakes are refused. Google
///    changed its key prefix once already and an app that hardcodes `AIza` is
///    an app that is broken until it ships an update.
struct KeySetupView: View {

    /// Called once a key has been verified and stored.
    var onSaved: () -> Void

    @State private var provider: KeyProvider = .gemini
    @State private var pastedKey = ""
    @State private var isVerifying = false
    @State private var problem: String?
    @FocusState private var keyFieldFocused: Bool

    private let keys = ProviderKeyStore()
    private let verifier = ProviderKeyVerifier()

    var body: some View {
        ZStack {
            BrandColor.ink.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: BrandSpacing.lg) {
                    header
                    providerPicker
                    keyField
                    if let problem {
                        Text(problem)
                            .font(.system(.footnote, design: .rounded).weight(.medium))
                            .foregroundStyle(BrandColor.ember)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityAddTraits(.isStaticText)
                    }
                    consoleLink
                    saveButton
                    reassurance
                }
                .padding(BrandSpacing.md)
            }
        }
        .onChange(of: pastedKey) { _ in
            // Clear a stale rejection as soon as the user edits, so the screen
            // never argues with what is currently in the field.
            if problem != nil { problem = nil }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: BrandSpacing.xs) {
            Text("Add your API key")
                .font(.system(.title2, design: .rounded).weight(.bold))
                .foregroundStyle(BrandColor.text)
            Text(
                "earslate runs on your own Gemini or OpenAI account. "
                + "Sessions are billed to you, at your provider's rates, and no ClassEve server is involved."
            )
            .font(.system(.subheadline, design: .rounded))
            .foregroundStyle(BrandColor.muted)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var providerPicker: some View {
        Picker("Provider", selection: $provider) {
            ForEach(KeyProvider.allCases) { candidate in
                Text(candidate.displayName).tag(candidate)
            }
        }
        .pickerStyle(.segmented)
    }

    private var keyField: some View {
        VStack(alignment: .leading, spacing: BrandSpacing.xs) {
            SecureField("Paste your \(provider.displayName) key", text: $pastedKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .focused($keyFieldFocused)
                .padding(BrandSpacing.md)
                .background(BrandColor.panelRaised)
                .foregroundStyle(BrandColor.text)
                .clipShape(RoundedRectangle(cornerRadius: BrandRadius.md, style: .continuous))
                .disabled(isVerifying)
                .accessibilityLabel("\(provider.displayName) API key")

            if let other = otherProviderHint {
                // A hint, never a refusal — the user may know something we don't.
                Text("That looks like a \(other.displayName) key. You can still save it here.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(BrandColor.quiet)
            }
        }
    }

    private var consoleLink: some View {
        Link(destination: provider.consoleURL) {
            HStack(spacing: BrandSpacing.xs) {
                Image(systemName: "arrow.up.forward.square")
                Text("Get a key from \(provider.consoleName)")
            }
            .font(.system(.footnote, design: .rounded).weight(.semibold))
            .foregroundStyle(BrandColor.ember)
        }
    }

    private var saveButton: some View {
        Button {
            Task { await save() }
        } label: {
            HStack(spacing: BrandSpacing.sm) {
                if isVerifying { ProgressView().tint(BrandColor.text) }
                Text(isVerifying ? "Checking with \(provider.displayName)…" : "Save and verify")
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(isVerifying || pastedKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private var reassurance: some View {
        Text(
            "Your key is stored in the iOS Keychain on this device only — it is not included in "
            + "iCloud Keychain or in backups. It is sent once, over HTTPS, to \(provider.displayName) "
            + "to open a short-lived session; the live connection never carries it."
        )
        .font(.system(.caption, design: .rounded))
        .foregroundStyle(BrandColor.quiet)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var otherProviderHint: KeyProvider? {
        let trimmed = pastedKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix(provider.prefixHint) else { return nil }
        guard let detected = KeyProvider.detect(trimmed), detected != provider else { return nil }
        return detected
    }

    private func save() async {
        let candidate = pastedKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if let reason = provider.rejectionReason(candidate) {
            problem = reason
            return
        }
        keyFieldFocused = false
        isVerifying = true
        defer { isVerifying = false }

        // Verified against the language the app will actually request, so a key
        // that cannot reach the translate model fails here rather than at the
        // first real session.
        switch await verifier.verify(provider: provider, apiKey: candidate, targetLanguage: "en") {
        case .valid:
            guard keys.save(candidate, for: provider) else {
                problem = "The key could not be saved to the Keychain on this device."
                return
            }
            pastedKey = ""
            onSaved()
        case .rejected(let message):
            problem = message
        }
    }
}
