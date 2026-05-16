# revive

**Offline emergency communication and mesh messaging platform.** revive enables peer-to-peer communication without internet or cellular coverage, using Bluetooth Low Energy and Google Nearby Connections for disaster scenarios and remote areas.

## Features

- **P2P Chat** — One-to-one messaging via Google Nearby Connections (P2P_CLUSTER)
- **Mesh Network** — Multi-hop mesh messaging with automatic device discovery and message relay
- **Emergency SOS** — Triple-press power button triggers SMS alerts, BLE/WiFi Direct SOS broadcasting
- **End-to-End Encryption** — RSA key exchange + AES-GCM message encryption via Android Keystore
- **Offline Operation** — No internet or cell service required; pure peer-to-peer
- **Material 3 Design** — Dynamic color theming with Jetpack Compose

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose, Material 3 (BOM 2024.09.00) |
| Networking | Google Nearby Connections API, BLE GATT |
| Database | Room 2.6.1 (kapt) |
| Serialization | kotlinx-serialization 1.6.2 |
| Encryption | Android Keystore (RSA + AES-GCM) |
| Build | AGP 8.13, Gradle 8.x |
| Min SDK | 24 |
| Target SDK | 36 |

## Architecture

```
MainActivity (3 tabs)
├── Tab 0: Chat (P2P) — NearbyManager + ChatViewModel
├── Tab 1: SOS — SOSManager, SOSAdvertiser, SOSDetector
└── Tab 2: Mesh — MeshChatViewModel + NearbyMeshManager
                        ├── MeshModels / MeshDatabase (Room)
                        ├── MeshRoutingManager
                        └── MeshEncryptionManager
```

- **`NearbyMeshManager`** — Primary mesh implementation using Google Nearby Connections `P2P_STAR` with automatic message relay to all connected peers
- **Chat tab** uses its own dedicated `NearbyManager` for simple 1-to-1 connections via Nearby Connections `P2P_CLUSTER`
- **SOS system** monitors triple power-button presses via `ImprovedPowerButtonReceiver` (foreground service keeps alive), sends SMS to emergency contacts, and broadcasts SOS signal over BLE + WiFi Direct

## Building

```sh
./gradlew assembleDebug              # full debug build
./gradlew lintDebug                  # lint check (baseline suppresses known issues)
./gradlew testDebugUnitTest          # unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests (requires device/emulator)
```

Open in Android Studio and run on a device. Gradle wrapper is included.

## Permissions

The app requests Bluetooth, Location, SMS, and Notification permissions at runtime. Android 12+ requires `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` in addition to legacy Bluetooth permissions.

## Usage

1. **Chat** — Tap "Advertise" to make your device visible, tap "Discover" to find nearby devices
2. **SOS** — Press the power button 3 times quickly to trigger emergency alerts. Configure emergency contacts from the SOS tab.
3. **Mesh** — The Mesh tab automatically discovers and connects to nearby peers. Messages are relayed through the network to reach devices out of direct range.

## Project Status

This is an active development project. See [CONTRIBUTING](CONTRIBUTING.md) for guidelines.

## License

```
Copyright 2024 revive contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
