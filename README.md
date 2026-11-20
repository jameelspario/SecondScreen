# SecondScreen

Turn your Android phone or tablet into a high-performance, low-latency secondary display for your Mac over USB or Wi-Fi.

## Architecture

- **MacHost (macOS Host App):**
  - **Virtual Display:** Uses Apple CoreGraphics private APIs (`CGVirtualDisplay`) to instantiate a hardware-accelerated virtual monitor with customizable resolution and refresh rate.
  - **Screen Capture:** Leverages `ScreenCaptureKit` for ultra-low latency, zero-copy frame capture.
  - **Hardware Encoding:** Encodes BGRA frames to H.264 Annex-B using `VideoToolbox` (`VTCompressionSession`) with baseline profile and instant IDR keyframe generation.
  - **Streaming Server:** High-performance TCP streaming server via Network framework broadcasting mDNS Bonjour service `_secondscreen._tcp`.
  - **Reverse Touch Channel:** Injects native mouse down, drag, and up `CGEvents` mapped from normalized touch coordinates received from the Android device.

- **SecondScreenAndroid (Android Receiver App):**
  - **Hardware Decoder:** Uses `MediaCodec` direct-to-surface rendering with Annex-B stream parsing and SPS/PPS keyframe synchronization.
  - **UI & Interaction:** Jetpack Compose connection interface + edge-to-edge fullscreen `SurfaceView` with multi-touch event forwarding.
  - **Connection Modes:** Auto-discovers Macs over Wi-Fi via `NsdManager` or connects over USB via `adb reverse`.

---

## Getting Started

### 1. Mac Host Setup
1. Open `MacHost/MacHost.xcodeproj` in Xcode.
2. Build and run `MacHost`.
3. In macOS **System Settings $\to$ Privacy & Security $\to$ Screen & System Audio Recording**, grant permission to **MacHost** (quit and reopen when prompted).
4. Click **Start Second Screen Server** in MacHost.

### 2. Android Setup
1. Open `SecondScreenAndroid` in Android Studio or build via CLI:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### 3. Connection Modes

#### Over USB (Ultra-low latency):
1. Connect phone via USB with USB Debugging enabled.
2. On your Mac, forward port 7070:
   ```bash
   adb reverse tcp:7070 tcp:7070
   ```
3. In the Android app, select the **USB** tab and tap **Connect via USB**.

#### Over Wi-Fi:
1. Ensure your Mac and Android device are on the same Wi-Fi network.
2. In the Android app, select the **WiFi** tab.
3. Tap on **SecondScreen Mac** from the discovered hosts list (or enter your Mac's IP manually).
