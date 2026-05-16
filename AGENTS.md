# revive (ToneSave) — AGENTS.md

## Build & Run

```sh
./gradlew assembleDebug          # full build
./gradlew lintDebug              # lint (baseline suppresses known issues)
./gradlew testDebugUnitTest      # unit tests (only placeholder tests exist)
./gradlew connectedDebugAndroidTest  # instrumented tests (requires device/emulator)
```

- **AGP 8.13**, Kotlin 2.0.21, Compose BOM 2024.09.00, Room 2.6.1 (kapt), kotlinx-serialization 1.6.2
- kotlin-kapt for Room — any Room schema changes require a clean build
- lint baseline at `app/lint-baseline.xml` — existing issues are suppressed

## Architecture — Fixed Issues

### Mesh Stack (consolidated)

`MeshChatViewModel` now uses `NearbyMeshManager` (Google Nearby Connections `P2P_STAR`) — the intended replacement for the old custom BLE stack. `BluetoothMeshManager.kt` remains as dead code (not referenced anywhere).

Three chat UIs were consolidated; the standalone `ChatScreen.kt` was dead code and has been removed. The inline `ChatScreen()` in `MainActivity.kt` is the single P2P chat implementation.

### SOS Receivers (consolidated)

`PowerButtonReceiver` and `SOSReceiver` were duplicates — all three receivers fired on the same triple-press. Only `ImprovedPowerButtonReceiver` remains registered in the manifest. `SOSService` no longer dynamically registers a separate receiver. Dead receiver files removed.

## Key Code Locations

| What | File |
|------|------|
| Entry point + tab navigation | `MainActivity.kt` (contains inline ChatScreen + EmergencySOSScreen + inline MeshChatScreen) |
| Mesh ViewModel (NearbyMeshManager based) | `mesh/MeshChatViewModel.kt` |
| Mesh data models + Room entities | `mesh/MeshModels.kt` |
| Mesh DB + DAOs | `mesh/MeshDatabase.kt`, `mesh/MeshDatabaseProvider.kt` |
| E2E encryption | `mesh/MeshEncryptionManager.kt` (RSA keystore + AES-GCM) |
| Routing logic | `mesh/MeshRoutingManager.kt` |
| Working P2P chat manager | `NearbyManager.kt` |
| SOS SMS + notification logic | `SOSManager.kt` |
| SOS BLE/WiFi advertising | `SOSAdvertiser.kt` (singleton) |
| SOS BLE/WiFi scanning | `SOSDetector.kt` |
| Emergency contact management | `EmergencyContactsActivity.kt` |
| Theme (Material 3 + dynamic color) | `ui/theme/` |

## Conventions

- `mutableStateOf` / `mutableStateListOf` for Activity-level state (no SavedStateHandle)
- SharedPreferences for persistence (SOS contacts, mesh username, press timings)
- `SOSAdvertiser` uses double-checked locking singleton
- All Bluetooth operations guarded by `SecurityException` catches for runtime permission loss
- Version catalog at `gradle/libs.versions.toml` — some deps (`play-services-nearby`, `room`, etc.) declared inline in `app/build.gradle.kts` instead
