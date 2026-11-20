import SwiftUI

struct ContentView: View {
    @StateObject private var session = StreamingSession()

    var body: some View {
        HostDashboardView(session: session)
    }
}
