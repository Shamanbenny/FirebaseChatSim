# Firebase Multi-Client Chat Simulator (FirebaseChatSim)

## Overview
Serverless Kotlin/Android project simulating a multi-user chat room using Firebase Realtime 
Database. Multiple "client" instances connect simultaneously to send/receive messages in 
real-time, demonstrating Firebase sync capabilities without complex Android UI.

## Learning Objectives
- Firebase Realtime Database: real-time listeners, push operations, JSON data modeling
- Kotlin: coroutines/async for non-blocking I/O, Firebase KTX extensions
- Multi-client simulation: run identical app on multiple emulators/devices
- Serverless architecture: no backend servers required

## Tech Stack
- **Language**: Kotlin
- **Platform**: Android (minimal UI)
- **Backend**: Firebase Realtime Database (test mode)
- **IDE**: Android Studio
- **Min SDK**: API 24

## Prerequisites
1. Android Studio (latest stable)
2. Firebase account (console.firebase.google.com)
3. 2+ Android emulators or physical devices
4. Google Play Services enabled on emulators

## Setup Instructions

### 1. Create Android Studio Project
```
File → New → New Project → Empty Activity
 - Name: FirebaseChatSim
 - Package: com.example.firebasechatsim
 - Language: Kotlin
 - Min SDK: API 24
```

### 2. Connect to Firebase
```
**Tools → Firebase → Realtime Database → "Get started with Realtime Database" → "Connect to Firebase"
Afterwards, select "Add the Realtime Database SDK to your app"**
```

### 3. Implement Chat Client
```
TBC...
```

### 4. Run Multi-Client Simulation
1. Create 2-3 AVDs (API 30+)
2. **Run → Run 'app'** on first emulator
3. **Run → Run 'app'** on second emulator (same APK)
4. Watch Logcat for real-time messages across clients
   (Filter with `package:mine tag:FirebaseChatSim is:debug`)

## Testing Checklist
- [X] Messages appear instantly across all clients
- [X] Each client has unique ID
- [X] Database shows JSON tree in Firebase Console
- [X] Test mode allows read/write
- [X] Multiple emulators sync simultaneously
- [X] Clients are assigned a unique clientId via Anonymous Firebase Authentication
- [ ] Move from Status listener to Direct Messaging