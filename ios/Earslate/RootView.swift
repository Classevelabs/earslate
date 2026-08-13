import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: TranslationViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if model.needsKey {
                // No key means no session is possible. Showing the normal
                // screen with a live-looking Start button would be a UI that
                // lies: it can only ever produce an error.
                KeySetupView { model.refreshKeyState() }
            } else {
                translator
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refreshKeyState() }
        }
    }

    private var translator: some View {
        ZStack {
            BrandColor.ink.ignoresSafeArea()

            VStack(spacing: BrandSpacing.lg) {
                header

                VStack(spacing: BrandSpacing.md) {
                    statusStrip
                    providerControl
                    languageControl
                    captionSurface
                    controls
                }
                .padding(.horizontal, BrandSpacing.md)

                Spacer(minLength: BrandSpacing.md)
            }
            .padding(.top, BrandSpacing.lg)
        }
        .alert("Earslate", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }

    private var header: some View {
        HStack(spacing: BrandSpacing.md) {
            ZStack {
                RoundedRectangle(cornerRadius: BrandRadius.md, style: .continuous)
                    .fill(BrandColor.ember)
                Image(systemName: "waveform.and.person.filled")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(BrandColor.text)
            }
            .frame(width: 52, height: 52)

            VStack(alignment: .leading, spacing: 3) {
                Text("Earslate")
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .foregroundStyle(BrandColor.text)
                Text("Live translation")
                    .font(.system(.callout, design: .rounded).weight(.medium))
                    .foregroundStyle(BrandColor.muted)
            }

            Spacer()

        }
        .padding(.horizontal, BrandSpacing.md)
    }

    private var statusStrip: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.state.rawValue)
                    .font(.system(.headline, design: .rounded).weight(.semibold))
                    .foregroundStyle(BrandColor.text)
                Text("Direct provider session")
                    .font(.system(.caption, design: .rounded).weight(.medium))
                    .foregroundStyle(BrandColor.muted)
            }

            Spacer()

            Circle()
                .fill(statusColor)
                .frame(width: 10, height: 10)
        }
        .padding(BrandSpacing.md)
        .background(BrandColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: BrandRadius.md, style: .continuous))
    }

    private var captionSurface: some View {
        ScrollView {
            Text(model.caption.isEmpty ? "Translated speech will appear here." : model.caption)
                .font(.system(.title3, design: .rounded).weight(.medium))
                .foregroundStyle(model.caption.isEmpty ? BrandColor.quiet : BrandColor.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(BrandSpacing.md)
        }
        .frame(maxWidth: .infinity, minHeight: 280)
        .background(BrandColor.panelRaised)
        .clipShape(RoundedRectangle(cornerRadius: BrandRadius.md, style: .continuous))
    }

    private var languageControl: some View {
        HStack(spacing: BrandSpacing.sm) {
            Image(systemName: "globe")
                .foregroundStyle(BrandColor.ember)
            Text("Translate to")
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(BrandColor.muted)
            Spacer()
            Picker("Target language", selection: $model.targetLanguage) {
                ForEach(TranslationViewModel.supportedTargetLanguages, id: \.self) { language in
                    Text(language).tag(language)
                }
            }
            .pickerStyle(.menu)
            .tint(BrandColor.text)
            .disabled(!canChangeLanguage)
        }
        .padding(.horizontal, BrandSpacing.md)
        .padding(.vertical, BrandSpacing.sm)
        .background(BrandColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: BrandRadius.md, style: .continuous))
        .opacity(canChangeLanguage ? 1 : 0.72)
    }

    private var providerControl: some View {
        HStack(spacing: BrandSpacing.sm) {
            Image(systemName: "bolt.horizontal.circle")
                .foregroundStyle(BrandColor.ember)
            Text("Provider")
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(BrandColor.muted)
            Spacer()
            Picker("Translation provider", selection: $model.provider) {
                ForEach(TranslationProvider.allCases) { provider in
                    Text(provider.title).tag(provider)
                }
            }
            .pickerStyle(.menu)
            .tint(BrandColor.text)
            .disabled(!canChangeLanguage)
        }
        .padding(.horizontal, BrandSpacing.md)
        .padding(.vertical, BrandSpacing.sm)
        .background(BrandColor.panel)
        .clipShape(RoundedRectangle(cornerRadius: BrandRadius.md, style: .continuous))
        .opacity(canChangeLanguage ? 1 : 0.72)
    }

    private var controls: some View {
        HStack(spacing: BrandSpacing.sm) {
            switch model.state {
            case .idle, .failed:
                Button {
                    model.start()
                } label: {
                    Label("Start", systemImage: "mic")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            default:
                Button {
                    Task { await model.stop() }
                } label: {
                    Label("Stop", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(MatteButtonStyle())
            }
        }
    }

    private var canChangeLanguage: Bool {
        switch model.state {
        case .bootstrapping, .connecting, .listening, .playing, .reconnecting:
            return false
        default:
            return true
        }
    }

    private var statusColor: Color {
        switch model.state {
        case .listening, .playing:
            return BrandColor.green
        case .failed:
            return BrandColor.ember
        default:
            return BrandColor.muted
        }
    }
}
