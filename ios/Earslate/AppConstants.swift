import Foundation

enum AppConstants {
    static let workerURL = URL(string: "https://api.classeve.com") ?? URL(fileURLWithPath: "/")
    static func workerEndpoint(_ path: String, base: URL = workerURL) -> URL {
        path
            .split(separator: "/")
            .reduce(base) { url, component in
                url.appendingPathComponent(String(component))
            }
    }
}
