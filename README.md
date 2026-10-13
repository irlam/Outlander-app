# Outlander App (Android)

Android remote-control app for Mitsubishi Outlander PHEV Wi‑Fi systems.

## Current status (implemented)

This repository already includes a completed first pass of UI polish and connection-speed improvements.

### UI/UX improvements completed

- Larger, more accessible touch targets (48dp actions/buttons).
- Improved layout rhythm and readability across main screen, settings dialog, and logs sheet.
- Staged entry animation for top-level sections (`headerBar`, `cardConnection`, `cardBattery`, `cardSecurity`, `cardClimate`, `cardLights`, `cardTelemetry`).
- Micro-interactions on key actions (tap bounce feedback).
- Animated connection state transitions:
  - pulsing status dot while connecting/reconnecting
  - success pop animation when connected
- Animated battery percentage/progress transitions.
- Settings dialog and logs sheet entrance motion.

### Connection speed and resilience improvements completed

- Fast-path reuse of active car Wi‑Fi network when SSID already matches.
- Socket connect timeout reduced from `5000ms` to `2500ms`.
- Reconnect cadence changed from fixed `3s` to adaptive `0.9s → 2.6s`.
- Wi‑Fi request timeout reduced from `60s` to `25s` for faster failure feedback.
- Socket tuning enabled (`tcpNoDelay`, `keepAlive`).
- Connection status updates centralized (`updateConnectionUi(...)`) for consistent UX.

## Key files to know

- `app/src/main/java/com/outlander/remote/MainActivity.kt`  
  Primary UI logic, connection flow, animations, dialogs, and telemetry rendering.
- `app/src/main/java/com/outlander/remote/CarClient.kt`  
  PHEV protocol client loop, frame send/receive, climate/lights commands.
- `app/src/main/res/layout/activity_main.xml`  
  Main dashboard UI sections/cards.
- `app/src/main/res/layout/dialog_settings.xml`  
  Vehicle Wi‑Fi settings UI.
- `app/src/main/res/layout/sheet_logs.xml`  
  Diagnostic log bottom sheet UI.

## Build

```bash
./gradlew assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## AI handoff notes (for future updates)

When making further AI-assisted changes:

1. Keep all new UI behavior consistent with existing animation style in `MainActivity.kt`.
2. Preserve and reuse `updateConnectionUi(...)` for all connection-state transitions.
3. Avoid adding blocking work on the UI thread; network/socket operations should stay backgrounded.
4. Favor small, surgical changes; verify with `assembleDebug` before finalizing.
5. If changing connection logic, keep fast retry behavior and clear user feedback for connect/reconnect/failure states.

