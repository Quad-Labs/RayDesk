# RayDesk v1.0 Release

## Overview
Remote desktop streaming client for RayNeo X3 Pro AR glasses using Moonlight/Sunshine.

## Features
- **Moonlight/Sunshine streaming** - HEVC/H.264 low-latency HW decode via SurfaceTexture
- **Three display modes** - Keyhole panning, floating monitor, curved monitor
- **Head tracking** - 219Hz TYPE_GAME_ROTATION_VECTOR with One-Euro filtering
- **Trackpad input** - Temple gesture-based cursor control with tap/swipe/scroll
- **BT mouse support** - Full mouse input passthrough
- **Virtual environment** - Skybox, dashboard, status ring, physical monitor frame
- **Screen-space HUD** - Connection quality bars (wired to real RTT/frame loss), control hints
- **Game-style radial menu** - Settings overlay with zoom, display mode, environment themes
- **Multi-monitor switching** - Via Sunshine Ctrl+Alt+Shift+F1-F12 hotkeys
- **Auto-reconnection** - Resilient streaming with exponential backoff
- **Server discovery** - mDNS auto-discovery + manual IP entry + PIN pairing

## Changes from Development Build

### Removed
- 4 test activities (CapabilityTest, HeadGazeCursorTest, TrackpadTest, UsbHidTest)
- 3 test activity layouts
- Test activity declarations from AndroidManifest.xml
- Empty config directory

### Logging Cleanup
- Removed **142 Log.d()** calls (development debug logging)
- Removed **1 Log.v()** call (verbose perf logging)
- Removed **~30 development-tagged Log.i()** calls (`[ZOOM-DEBUG]`, `[ZOOM-APPLY]`, `[ZOOM-RENDERER]`, `[CURSOR-TRACKING-INIT]`, `[CURSOR-TRACKING-SAVE]`, `[VSC-RECENTER]`, `[VSC-POSE]`, `[BUILD-INPUT]`, `[SETTINGS] Loaded/Enforcing`, `[INIT] StreamingActivity/Display mode/Trackpad/Game menu`, `[TEST]`, `[ZOOM] detent`)
- Removed **3 TODO** comments
- Removed debug stack trace logging from `KeyholeViewport.cursorTrackingEnabled` setter
- Removed throttled debug log from `KeyholeViewport.computeViewport()` hot path
- **Retained** 224 diagnostic Log.i/w/e calls for production monitoring

### Bug Fixes (from code review)
- **Fixed premature `isStreaming` flag** — was set in `initVideoProvider()` before Moonlight connected, could cause RTT polling on null connection
- **Added missing `return`** after null server params check in `onCreate()` — was falling through to initialize with null UUID/address
- **Added `isFinishing` guard** to quality polling runnable — prevents JNI call after activity teardown
- **Added `@Volatile`** to cross-thread fields in `StreamRenderer` (`displayMode`, `headYawDegrees`, `headPitchDegrees`, `cursorX`, `cursorY`, `testPatternEnabled`)
- **Removed debug stack trace logging** from `KeyholeViewport.cursorTrackingEnabled` setter
- **Removed throttled debug log** from `KeyholeViewport.computeViewport()` hot path

### Known Limitations (for future releases)
- **C1:** `MOONLIGHT_UNIQUE_ID` is hardcoded — should generate per-device UUID
- **C2:** Private keys stored in plain SharedPreferences — should use EncryptedSharedPreferences
- **I1:** `StreamingActivity` is 2000+ lines — extract InputManager and SessionManager
- **I6:** Sensor sends 219 mouse packets/sec — throttle network sends to ~60fps
- **I7:** Settings save on every zoom swipe increment — debounce saves

### Line Count
- Source: 17,358 lines (57 production files)
- Launch: 17,162 lines (57 production files)
- Net reduction: 196 lines of debug noise removed + bug fixes applied

## File Structure
```
launch/
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/raydesk/
        data/         - Settings, server data, theme definitions
        gl/           - OpenGL renderers (StreamRenderer, CylinderMesh, ShaderUtils)
        gl/environment/ - Virtual environment (skybox, dashboard, status ring, HUD)
        input/        - Head gaze cursor
        spatial/      - Viewport math (keyhole, cylinder, virtual screen, filters)
        streaming/    - Moonlight bridge, discovery, reconnection
        test/         - RayDeskApplication.kt (SDK init - required)
        ui/           - Activities and overlays
        video/        - Video pipeline (GLTextureRenderer, FrameSlot, VideoTextureProvider)
      res/            - Layouts, shaders, resources
```

## Build
```bash
# Requires: Mercury SDK AAR, Moonlight fork, Amazon Corretto JDK 17
cd Standard && ./gradlew.bat assembleDebug
```

## Target Device
RayNeo X3 Pro (ARGF20, 1280x480 binocular, Android 12+)
