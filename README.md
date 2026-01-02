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

### 2.5. **[IMPORTANT]** As a reminder, *NEVER* commit `app/google-services.json`.
This should have been handled by updating `.gitignore` in project root. (You have been warned!)

### 3. Run Multi-Client Simulation
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
- [X] Incorporate [DKLS](https://dkls23.silencelaboratories.com/docs/dkls23/index.html) to allow for Threshold Signature Scheme system
- [X] Successfully perform DKG
- [ ] Allows [Key Refresh](https://dkls23.silencelaboratories.com/docs/dkls23/#key-refresh) for lost devices/keyshare
- [ ] Uses key signing for "add friend", "link device", etc.

## Importing [dkls-wrapper](https://github.com/kshehata/dkls-wrapper/tree/main) as Modules

### 1. In Android Studio: **File → New Module → Android Library** (call it e.g. `dklswrapper`).

### 2. In that module:
- Place the generated Kotlin UniFFI files into `dklswrapper/src/main/java`.
- Place the generated `jniLibs` folder into `dklswrapper/src/main/jniLibs`.

### 3. In `dklswrapper/build.gradle.kts`, add UniFFI runtime dependencies:
```
dependencies {
    implementation("net.java.dev.jna:jna:5.12.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}
```
Reference: [UniFFI User Guide - Kotlin's Integrating with Gradle](https://mozilla.github.io/uniffi-rs/latest/kotlin/gradle.html)

### 4. Ensure that the module is included in your `settings.gradle` and that your app module depends on it:
```
// Within settings.gradle ...
include(":app", ":dklsbindings")

// Within app/build.gradle ...
dependencies {
    implementation(project(":dklsbindings"))
}
```

### 5. Sync Gradle with your project.

### 6. Now, you should be able to load the DKLS native library in Kotlin.
```
import uniffi.dkls.*
// ...
class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("dkls") // matches libdkls.so
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // now DKLS UniFFI-generated classes can call into Rust
    }
}
```

### Refer to [`dklswrapper.md`](docs/dklswrapper.md) for API documentation surrounding `dklswrapper` module.