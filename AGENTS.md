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

## Architecture Warning — Duplicated Mesh Stacks

**Two competing mesh implementations exist, both half-wired:**

| File | Approach | Status |
|------|----------|--------|
| `mesh/NearbyMeshManager.kt` | Google Nearby Connections `P2P_STAR` | Clean, functional, but **not used** by ViewModel |
| `mesh/BluetoothMeshManager.kt` | Custom BLE GATT (scan/advertise/send) | 976 lines, `sendPacketToNextHop()` and `initiateRouteDiscovery()` are **no-ops** — routing never actually sends |

`MeshChatViewModel` references `BluetoothMeshManager` only. `NEARBY_CONNECTIONS_MESH.md` states Nearby was the intended replacement — this migration is incomplete.

**Three chat UIs exist:**
1. `MainActivity.kt:283` inline `ChatScreen()` — uses `NearbyManager` directly, fully functional
2. `ChatScreen.kt` — standalone composable expecting `ChatViewModel + NearbyManager` params, never called
3. `mesh/ui/MeshChatScreen.kt` — Mesh tab using `MeshChatViewModel`, references `BluetoothMeshManager` internally

## SOS System — Triple Trigger Bug

**Three competing broadcast receivers all listen for the same intents:**

- `PowerButtonReceiver` (2s window, 3 presses)
- `ImprovedPowerButtonReceiver` (3s window, 3 presses)
- `SOSReceiver` (2s window, 3 presses)

All registered in `AndroidManifest.xml`. A triple-press fires all three ≈ SOS triggers 3x.

## Key Code Locations

| What | File |
|------|------|
| Entry point + tab navigation | `MainActivity.kt` (also contains inline ChatScreen + EmergencySOSScreen + inline MeshChatScreen) |
| Mesh ViewModel (BluetoothMesh based) | `mesh/MeshChatViewModel.kt` |
| Mesh data models + Room entities | `mesh/MeshModels.kt` |
| Mesh DB + DAOs | `mesh/MeshDatabase.kt`, `mesh/MeshDatabaseProvider.kt` |
| E2E encryption | `mesh/MeshEncryptionManager.kt` (RSA keystore + AES-GCM) |
| Routing logic | `mesh/MeshRoutingManager.kt` (pure modeling, no actual packet delivery) |
| Working P2P chat manager | `NearbyManager.kt` |
| SOS SMS + notification logic | `SOSManager.kt` |
| SOS BLE/WiFi advertising | `SOSAdvertiser.kt` (singleton) |
| SOS BLE/WiFi scanning | `SOSDetector.kt` |
| Emergency contact management | `EmergencyContactsActivity.kt` |
| Theme (Material 3 + dynamic color) | `ui/theme/` |

## Conventions

- `mutableStateOf` / `mutableStateListOf` for Activity-level state (no SavedStateHandle)
- SharedPreferences for persistence (SOS contacts, mesh username, press timings)
- `ApplicationContextProvider` singleton object used as static context holder — fragile
- `SOSAdvertiser` uses double-checked locking singleton
- All Bluetooth operations guarded by `SecurityException` catches for runtime permission loss
- Version catalog at `gradle/libs.versions.toml` — some deps (`play-services-nearby`, `room`, etc.) declared inline in `app/build.gradle.kts` instead
