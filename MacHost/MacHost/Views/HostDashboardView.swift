import SwiftUI

struct HostDashboardView: View {
    @ObservedObject var session: StreamingSession

    @State private var selectedWidth: Int = 1920
    @State private var selectedHeight: Int = 1080
    @State private var useHiDPI: Bool = false
    @State private var selectedFPS: Double = 60.0
    @State private var portText: String = "7070"
    @State private var copiedAdbCommand: Bool = false
    @State private var hasPermission: Bool = ScreenCaptureSession.hasScreenRecordingPermission()

    private let resolutionPresets: [(label: String, w: Int, h: Int)] = [
        ("1920 × 1080 (16:9 FHD)", 1920, 1080),
        ("2400 × 1080 (20:9 Phone)", 2400, 1080),
        ("1280 × 720 (720p HD)", 1280, 720),
        ("2560 × 1440 (2K QHD)", 2560, 1440),
        ("1080 × 2400 (Portrait)", 1080, 2400)
    ]

    private var localIPAddress: String {
        var address: String = "127.0.0.1"
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return address }
        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            let addr = ptr.pointee.ifa_addr.pointee
            if (flags & (IFF_UP | IFF_RUNNING | IFF_LOOPBACK)) == (IFF_UP | IFF_RUNNING) {
                if addr.sa_family == UInt8(AF_INET) {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(ptr.pointee.ifa_addr, socklen_t(addr.sa_len),
                                   &hostname, socklen_t(hostname.count),
                                   nil, 0, NI_NUMERICHOST) == 0 {
                        address = String(cString: hostname)
                        break
                    }
                }
            }
        }
        freeifaddrs(ifaddr)
        return address
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header
                HStack {
                    Image(systemName: "display.2")
                        .font(.system(size: 32))
                        .foregroundStyle(.blue)
                    VStack(alignment: .leading) {
                        Text("SecondScreen Host")
                            .font(.title2.bold())
                        Text("Turn your Android phone/tablet into a secondary Mac display")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    // Status Badge
                    HStack(spacing: 6) {
                        Circle()
                            .fill(session.isStreaming ? (session.isClientConnected ? Color.green : Color.orange) : Color.gray)
                            .frame(width: 10, height: 10)
                        Text(statusText)
                            .font(.caption.bold())
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(.windowBackgroundColor)))
                }
                .padding()
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.controlBackgroundColor)))

                if !hasPermission {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 12) {
                            Image(systemName: "hand.raised.fill")
                                .foregroundStyle(.orange)
                                .font(.title2)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Screen Recording Permission Required")
                                    .font(.subheadline.bold())
                                Text("macOS requires Screen Recording permission to stream the virtual display to Android.\nImportant: macOS requires MacHost to be completely quit and restarted after enabling the toggle in System Settings.")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            VStack(spacing: 6) {
                                Button("Open Settings") {
                                    ScreenCaptureSession.requestScreenRecordingPermission()
                                    ScreenCaptureSession.openSystemSettings()
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(.orange)

                                Button("Quit App") {
                                    NSApplication.shared.terminate(nil)
                                }
                                .buttonStyle(.bordered)
                                .font(.caption)
                            }
                        }

                        Divider()

                        HStack {
                            Text("💡 If already enabled in System Settings:")
                                .font(.caption.bold())
                            Spacer()
                            Button("Reveal MacHost.app in Finder") {
                                NSWorkspace.shared.selectFile(Bundle.main.bundlePath, inFileViewerRootedAtPath: "")
                            }
                            .buttonStyle(.link)
                            .font(.caption)
                        }
                        Text("1. In System Settings -> Privacy & Security -> Screen & System Audio Recording, if MacHost is already in the list, remove it using the '-' button and re-add it (or drag it in).\n2. Toggle it ON.\n3. Click 'Quit App' above and restart MacHost so macOS applies the permission.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding()
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.orange.opacity(0.12)))
                }

                if let err = session.errorMessage {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                        Text(err)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Color.red.opacity(0.1)))
                }

                // Connection Info (WiFi + USB)
                VStack(alignment: .leading, spacing: 14) {
                    Text("Connection Modes")
                        .font(.headline)

                    HStack(alignment: .top, spacing: 16) {
                        // WiFi Card
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Image(systemName: "wifi")
                                    .foregroundStyle(.blue)
                                Text("WiFi (mDNS Auto-Discovery)")
                                    .font(.subheadline.bold())
                            }
                            Text("Ensure Mac and Android are on the same WiFi network.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Divider()
                            HStack {
                                Text("Mac IP:")
                                    .font(.caption.bold())
                                Text(localIPAddress)
                                    .font(.system(.caption, design: .monospaced))
                                    .textSelection(.enabled)
                                Spacer()
                                Text("Port: \(portText)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.controlBackgroundColor)))

                        // USB Card
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Image(systemName: "cable.connector")
                                    .foregroundStyle(.green)
                                Text("USB (Ultra Low Latency)")
                                    .font(.subheadline.bold())
                            }
                            Text("Plug Android via USB and run this command in Terminal:")
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            HStack {
                                Text("adb reverse tcp:7070 tcp:7070")
                                    .font(.system(.caption, design: .monospaced))
                                    .padding(6)
                                    .background(Color(.textBackgroundColor))
                                    .cornerRadius(6)

                                Button {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString("adb reverse tcp:7070 tcp:7070", forType: .string)
                                    copiedAdbCommand = true
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                        copiedAdbCommand = false
                                    }
                                } label: {
                                    Image(systemName: copiedAdbCommand ? "checkmark" : "doc.on.doc")
                                }
                                .buttonStyle(.borderless)
                            }

                            Text("Then tap 'Connect via USB' in the Android app.")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.controlBackgroundColor)))
                    }
                }

                // Display Settings
                VStack(alignment: .leading, spacing: 14) {
                    Text("Virtual Display Configuration")
                        .font(.headline)

                    HStack(spacing: 20) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Preset Resolution")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Picker("", selection: $selectedWidth) {
                                ForEach(resolutionPresets, id: \.w) { preset in
                                    Text(preset.label).tag(preset.w)
                                }
                            }
                            .labelsHidden()
                            .onChange(of: selectedWidth) { newWidth in
                                if let match = resolutionPresets.first(where: { $0.w == newWidth }) {
                                    selectedHeight = match.h
                                }
                            }
                            .disabled(session.isStreaming)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Refresh Rate")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Picker("", selection: $selectedFPS) {
                                Text("60 fps").tag(60.0)
                                Text("30 fps").tag(30.0)
                            }
                            .labelsHidden()
                            .pickerStyle(.segmented)
                            .disabled(session.isStreaming)
                        }

                        Toggle("HiDPI (Retina)", isOn: $useHiDPI)
                            .disabled(session.isStreaming)
                            .padding(.top, 16)
                    }
                }
                .padding()
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.controlBackgroundColor)))

                // Live Streaming Metrics
                if session.isStreaming {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Session Metrics")
                            .font(.headline)
                        HStack(spacing: 20) {
                            metricItem(title: "Frames Encoded", value: "\(session.framesSent)")
                            metricItem(title: "Target Bitrate", value: "8.0 Mbps")
                            metricItem(title: "Client", value: session.clientEndpoint ?? "Waiting for connection...")
                        }
                    }
                    .padding()
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color(.controlBackgroundColor)))
                }

                // Control Action Buttons
                HStack {
                    if !session.isStreaming {
                        Button {
                            Task {
                                let port = UInt16(portText) ?? 7070
                                await session.startSession(
                                    width: selectedWidth,
                                    height: selectedHeight,
                                    refreshRate: selectedFPS,
                                    hiDPI: useHiDPI,
                                    port: port
                                )
                            }
                        } label: {
                            Label("Start Second Screen Server", systemImage: "play.fill")
                                .frame(minWidth: 200, minHeight: 32)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.blue)
                    } else {
                        Button(role: .destructive) {
                            Task {
                                await session.stopSession()
                            }
                        } label: {
                            Label("Stop Server & Display", systemImage: "stop.fill")
                                .frame(minWidth: 200, minHeight: 32)
                        }
                        .buttonStyle(.borderedProminent)
                    }

                    Spacer()
                }
            }
            .padding(24)
        }
        .frame(minWidth: 640, minHeight: 480)
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            hasPermission = ScreenCaptureSession.hasScreenRecordingPermission()
        }
    }

    private var statusText: String {
        if !session.isStreaming {
            return "Stopped"
        } else if session.isClientConnected {
            return "Streaming Active"
        } else {
            return "Waiting for Android..."
        }
    }

    private func metricItem(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.bold())
        }
        .padding(8)
        .background(Color(.textBackgroundColor))
        .cornerRadius(6)
    }
}

