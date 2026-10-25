# Agent Development Guide: hqpe-dlna

## Project Mission
Develop a lightweight, non-root Android application (`hqpe-dlna`) that captures system media audio playback via `AudioPlaybackCapture` (Android 10+) and streams it as a standard L16/WAV live HTTP stream to HQPlayer Embedded (HQPE, target `10.10.10.120`) using strict, standard UPnP/DLNA SOAP commands.

---

## Environment & Infrastructure Constraints
1. **GitHub Credentials**:
   - Username: `qinsijie`
   - Auth Protocol: HTTPS Git with stored PAT token (`~/.git-credentials`). Do NOT use SSH (`git@github.com`).
   - Repository Target: `https://github.com/qinsijie/hqpe-dlna.git`
   - Repository Creation: Use GitHub REST API via `curl` with the stored PAT.
2. **Local Environment**:
   - Host: fnOS (Debian-based x86_64).
   - Android SDK/Gradle: Not locally installed. Local builds must NOT be attempted.
   - Compilation: Purely offloaded to GitHub Actions via `.github/workflows/build-apk.yml`.
3. **Commit Identity**:
   - Ensure local Git identity is configured before committing:
     `git config --global user.name "qinsijie"`
     `git config --global user.email "your_email@example.com"`

---

## Architectural Principles & Strict Protocols

### 1. Audio Capture (`MediaCaptureService`)
- Must run as a persistent Android **Foreground Service** with an ongoing notification.
- Use `MediaProjection` and `AudioPlaybackCaptureConfiguration.Builder`.
- Target audio attributes: `AudioAttributes.USAGE_MEDIA`.
- Audio format: `AudioFormat.ENCODING_PCM_16BIT`, `48000Hz`, `CHANNEL_IN_STEREO`.
- Must handle graceful start, stop, and clean release of audio buffers.

### 2. Local HTTP Live Stream Server (`LocalHttpServer`)
- Lightweight embedded HTTP server (e.g., NanoHTTPD or raw ServerSocket).
- Listen on `0.0.0.0:8088`, exposing endpoint `/stream.wav`.
- Must respond with:
  - `Content-Type: audio/wav`
  - `Transfer-Encoding: chunked`
- Output format: Continuous PCM stream prepended with a valid 44-byte standard RIFF/WAV header (configured with `0xFFFFFFFF` data length for continuous streams).

### 3. HQPE UPnP Controller (`HqpeClient`)
- Do NOT use bloated or non-compliant third-party UPnP libraries that emit invalid SSDP packets (e.g., missing `MAN: "ssdp:discover"` quotes).
- Use lightweight `OkHttp` to dispatch standard SOAP XML payloads directly:
  - Default Endpoint: `http://10.10.10.120:<HQPE_PORT>/upnp/control/rendertransport`
  - Required Headers:
    - `Content-Type: text/xml; charset="utf-8"`
    - `SOAPAction: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"`
    - `Host: 10.10.10.120:<HQPE_PORT>` (MUST include port number explicitly).
- Supported Actions:
  - `SetAVTransportURI`: Points to `http://<PHONE_IP>:8088/stream.wav`.
  - `Play`: Triggers immediate playback on HQPE.
  - `Stop`: Dispatched immediately upon user stop, audio release, or disconnect so HQPE frees the audio output device for Roon.

---

## Delivery Workflow for Agents
1. **Repository & Init**: Verify GitHub repo creation and set up the clean Git scaffold.
2. **Project Generation**:
   - Generate standard Android Gradle project (Kotlin DSL, `minSdk = 29`, `targetSdk = 34`).
   - Create standard Gradle Wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.properties`).
3. **CI Configuration**:
   - Provide `.github/workflows/build-apk.yml` targeting JDK 17 and `ubuntu-latest` running `./gradlew assembleDebug`.
4. **Push & Monitor**:
   - Push to `main` branch via HTTPS.
   - Confirm workflow trigger.