import SwiftUI

enum BrandColor {
    static let ink = Color(hex: 0x1A1612)
    static let panel = Color(hex: 0x221C16)
    static let panelRaised = Color(hex: 0x2A2218)
    static let stroke = Color(hex: 0x3A2F23)
    static let text = Color(hex: 0xECE3D2)
    static let muted = Color(hex: 0xB8AA8E)
    static let quiet = Color(hex: 0x80735C)
    static let ember = Color(hex: 0xC2410C)
    static let emberPressed = Color(hex: 0x9A3412)
    static let green = Color(hex: 0x2F7D5B)
}

enum BrandRadius {
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
}

enum BrandSpacing {
    static let xs: CGFloat = 6
    static let sm: CGFloat = 10
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        let red = Double((hex >> 16) & 0xff) / 255
        let green = Double((hex >> 8) & 0xff) / 255
        let blue = Double(hex & 0xff) / 255
        self.init(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.callout, design: .rounded).weight(.semibold))
            .foregroundStyle(BrandColor.text)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(configuration.isPressed ? BrandColor.emberPressed : BrandColor.ember)
            .clipShape(RoundedRectangle(cornerRadius: BrandRadius.sm, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

struct MatteButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.callout, design: .rounded).weight(.medium))
            .foregroundStyle(BrandColor.text)
            .frame(minHeight: 42)
            .padding(.horizontal, 12)
            .background(configuration.isPressed ? BrandColor.stroke : BrandColor.panelRaised)
            .clipShape(RoundedRectangle(cornerRadius: BrandRadius.sm, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
    }
}
