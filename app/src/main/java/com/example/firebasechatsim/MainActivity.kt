package com.example.firebasechatsim

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.firebasechatsim.ui.theme.FirebaseChatSimTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import uniffi.dkls.*
import java.util.Date

data class PresenceInfo(
    val clientId: String,
    val lastSeen: Long?
) {
    val isOnline: Boolean
        get() {
            if (lastSeen == null) return false
            val now = System.currentTimeMillis()
            // considered online if lastSeen within 20 seconds ago
            return now - lastSeen <= 20_000
        }
}

data class InviteData(
    val type: String?,
    val initiatorId: String?,
    val targetId: String?,
    val instanceIdBase64: String?,  // Base64-encoded InstanceId bytes
    val setupBase64: String?,       // Base64-encoded SetupMessage bytes
    val threshold: Int = 0,
    val total: Int = 0,
    val timestamp: Long = 0L,
    val status: String?
) {
    // Helper to convert back to DKLS objects
    fun toInstanceId(): InstanceId {
        val bytes = Base64.decode(instanceIdBase64, Base64.URL_SAFE + Base64.NO_WRAP)
        return InstanceId.fromBytes(bytes)
    }

    fun toSetupBytes(): ByteArray {
        val bytes = Base64.decode(setupBase64, Base64.URL_SAFE + Base64.NO_WRAP)
        return bytes
    }
}

class FirebaseNetworkInterface(
    private val database: DatabaseReference,
    private val instanceIdBase64: String,
    private val ownClientId: String,
    private val peerClientId: String
) : NetworkInterface {
    private val messagesRef = database.child("dkg_messages").child(instanceIdBase64)
    private val messageQueue = mutableListOf<DataSnapshot>()
    private var listener: ValueEventListener? = null
    private var listenerRegistered = false

    init {
        // Register listener once on init for continuous monitoring
        if (!listenerRegistered) {
            listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // Collect all current matching messages (to=peerClientId)
                    val newMessages = snapshot.children
                        .filter { msg ->
                            val from = msg.child("from").getValue(String::class.java)
                            val to = msg.child("to").getValue(String::class.java)
                            from == peerClientId && to == ownClientId
                        }
                        .toList()
                    messageQueue.addAll(newMessages)
                    //Log.d("FirebaseChatSim", "Listener added ${newMessages.size} peer messages to queue. Total queue: ${messageQueue.size}")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("FirebaseChatSim", "Receive listener cancelled", error.toException())
                }
            }
            messagesRef.addValueEventListener(listener!!)
            listenerRegistered = true
            Log.d("FirebaseChatSim", "Receive listener registered for peer $peerClientId")
        }
    }

    override suspend fun send(data: ByteArray) {
        try {
            val messagePush = messagesRef.push()
            val messageData = mapOf(
                "from" to ownClientId,
                "to" to peerClientId,
                "data" to Base64.encodeToString(data, Base64.URL_SAFE + Base64.NO_WRAP),
                "timestamp" to ServerValue.TIMESTAMP
            )
            messagePush.setValue(messageData).await()
            Log.d("FirebaseChatSim", "DKG send: ${data.size}B to $peerClientId")
        } catch (e: Exception) {
            Log.e("FirebaseChatSim", "DKG send failed", e)
            throw RuntimeException("DKG send failed", e)
        }
    }

    override suspend fun receive(): ByteArray {
        return withTimeoutOrNull(45000) {
            // Wait for and process next message in queue
            while (messageQueue.isEmpty()) {
                delay(100) // Poll queue efficiently
            }
            val message = messageQueue.removeAt(0) // Dequeue oldest
            val dataB64 = message.child("data").getValue(String::class.java)!!
            val data = Base64.decode(dataB64, Base64.URL_SAFE + Base64.NO_WRAP)
            message.ref.removeValue().await() // Ack-delete
            Log.d("FirebaseChatSim", "DKG receive: ${data.size}B from $peerClientId")
            data
        } ?: throw RuntimeException("DKG receive timeout")
    }
}

object DklsStatus {
    // Status codes
    const val STATUS_UNKNOWN = 0
    const val STATUS_NO_KEY = 1
    const val STATUS_KEY_READY = 2
    const val STATUS_KEYGEN_ERROR = 3
    const val STATUS_NETWORK_ERROR = 4
    const val STATUS_ERROR = 5

    // Current status code
    @Volatile
    var currentCode: Int = STATUS_UNKNOWN

    // Status messages mapped to codes
    private val statusMessages = mapOf(
        STATUS_UNKNOWN to "Initializing...",
        STATUS_NO_KEY to "⚠\uFE0F No keyshare yet\nWaiting for 'Add Device'...",
        STATUS_KEY_READY to "✅ Keyshare ready!\n",
        STATUS_KEYGEN_ERROR to "❌ Key Gen Error has occurred!",
        STATUS_NETWORK_ERROR to "❌ Network Error has occurred!",
        STATUS_ERROR to "❌ An Unexpected Error has occurred!"
    )

    // Get current status message
    fun getStatus(): String {
        return statusMessages[currentCode] ?: "Unknown status: $currentCode"
    }

    // Set status and return message
    fun setStatus(code: Int): String {
        currentCode = code
        return getStatus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    ownClientId: String,
    database: DatabaseReference,
    context: Context,
    dklsKeyshare: Keyshare?,
    onAddDevice: (String) -> Unit,
    onAcceptInvite: (InviteData) -> Unit
) {
    var inputClientId by remember { mutableStateOf("") }
    var dklsStatus by remember { mutableStateOf(DklsStatus.getStatus()) }
    val coroutineScope = rememberCoroutineScope()

    // Pending invites state
    var pendingInvites by remember { mutableStateOf<List<InviteData>>(emptyList()) }

    // Poll status every 500ms for reactivity
    LaunchedEffect(Unit) {
        while (true) {
            dklsStatus = DklsStatus.getStatus()
            delay(500)
        }
    }

    // Listen for incoming invites
    LaunchedEffect(ownClientId) {
        database.child("invites").child(ownClientId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val invites = snapshot.children.mapNotNull { child ->
                        try {
                            val inviteMap = child.value as? Map<*, *> ?: return@mapNotNull null
                            InviteData(
                                type = inviteMap["type"] as? String,
                                initiatorId = inviteMap["initiatorId"] as? String ?: child.key,  // ✅ Use child.key as fallback
                                targetId = inviteMap["targetId"] as? String,
                                instanceIdBase64 = inviteMap["instanceIdBase64"] as? String,
                                setupBase64 = inviteMap["setupBase64"] as? String,
                                threshold = (inviteMap["threshold"] as? Long)?.toInt() ?: 0,
                                total = (inviteMap["total"] as? Long)?.toInt() ?: 0,
                                timestamp = inviteMap["timestamp"] as? Long ?: 0L,
                                status = inviteMap["status"] as? String ?: "pending"
                            ).takeIf { it.initiatorId!!.isNotBlank() }  // Filter valid invites
                        } catch (e: Exception) {
                            Log.e("DKLS", "Failed to parse invite ${child.key}", e)
                            null
                        }
                    }
                    pendingInvites = invites.filter { it.status == "pending" }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("DKLS", "Invites listener cancelled", error.toException())
                }
            })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Your Client ID Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Your Client ID", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text(text = ownClientId)
                Button(
                    onClick = { ownClientId.copyToClipboard(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy to Clipboard")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        // DKLS Status Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "DKLS Status",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text(text = dklsStatus)
                if (DklsStatus.currentCode == DklsStatus.STATUS_NO_KEY) {
                    OutlinedTextField(
                        value = inputClientId,
                        onValueChange = { inputClientId = it },
                        label = { Text("Enter other device clientID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = { onAddDevice(inputClientId) },
                        enabled = inputClientId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Device")
                    }
                } else if (DklsStatus.currentCode == DklsStatus.STATUS_KEY_READY) {
                    // Keyshare display
                    dklsKeyshare?.let { keyshare ->
                        var keyshareText by remember { mutableStateOf("Computing...") }

                        // Compute display strings on LaunchedEffect
                        LaunchedEffect(keyshare) {
                            try {
                                keyshare.print()  // Logs to console for debugging
                                Log.d("FirebaseChatSim", "Keyshare printed to Logcat - check above")

                                // Extract exact fields Rust print() uses
                                val pkHex = hexString(keyshare.vk().toBytes())
                                val skHex = hexString(keyshare.sk())

                                keyshareText = "PK=$pkHex\nSK=$skHex"
                            } catch (e: Exception) {
                                keyshareText = "Print failed: ${e.message}"
                                Log.e("FirebaseChatSim", "Keyshare display failed", e)
                            }
                        }
                        Text(
                            text = keyshareText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Button(
                            onClick = { keyshareText.copyToClipboard(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Copy Full Keyshare")
                        }
                    }
                }
            }
        }

        // Pending Invites Card
        if (pendingInvites.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pending Invites (${pendingInvites.size})",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(pendingInvites) { invite ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "From: ${invite.initiatorId}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Threshold: ${invite.threshold}-of-${invite.total}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { coroutineScope.launch { onAcceptInvite(invite) } },
                                        modifier = Modifier.defaultMinSize(minWidth = 80.dp)
                                    ) {
                                        Text("Accept")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("dkls")  // matches `libdkls.so`
        }
    }
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private val TAG = "FirebaseChatSim"

    // DKLS state - stored locally only
    private var dklsKeyshare by mutableStateOf<Keyshare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Firebase Authentication
        auth = FirebaseAuth.getInstance()

        // Initialize Firebase Real-Time Database
        database = FirebaseDatabase.getInstance().reference

        val existingUser = auth.currentUser
        if (existingUser != null) {
            val clientId = existingUser.uid
            Log.d(TAG, "Welcome back! clientId = $clientId")
            startPresenceFlow(clientId)
            setupUi(clientId)
            updateDklsStatus()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val clientId = result.user?.uid ?: "unknown"
                    Log.d(TAG, "Signed in anonymously. clientId = $clientId")
                    startPresenceFlow(clientId)
                    setupUi(clientId)
                    DklsStatus.setStatus(DklsStatus.STATUS_NO_KEY)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous sign-in FAILED: ${e.message}")
                    DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                }
        }
    }

    private fun updateDklsStatus() {
        if (dklsKeyshare == null) {
            DklsStatus.setStatus(DklsStatus.STATUS_NO_KEY)
        } else {
            DklsStatus.setStatus(DklsStatus.STATUS_KEY_READY)
        }
    }

    private fun handleAddDevice(otherClientId: String) {
        if (otherClientId.isBlank()) return

        lifecycleScope.launch {
            // Check target presence
            database.child("presence").child(otherClientId).get()
                .addOnSuccessListener { snapshot ->
                    val lastSeen = snapshot.getValue(Long::class.java)
                    val now = System.currentTimeMillis()
                    val isOnline = lastSeen != null && (now - lastSeen) <= 12_000L

                    if (!isOnline) {
                        Toast.makeText(
                            this@MainActivity,
                            "Target device offline (last seen: ${lastSeen?.let { Date(it) } ?: "never"})",
                            Toast.LENGTH_LONG
                        ).show()
                        return@addOnSuccessListener
                    }

                    Toast.makeText(this@MainActivity, "Target online - sending invite...", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        sendDKGInvite(otherClientId, 2, 2)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this@MainActivity, "Presence check failed", Toast.LENGTH_SHORT).show()
                    DklsStatus.setStatus(DklsStatus.STATUS_NO_KEY)
                }
        }
    }

    private suspend fun sendDKGInvite(otherClientId: String, threshold: Int, total: Int) {
        try {
            val instanceId = InstanceId.fromEntropy()
            val instanceIdBytes = instanceId.toBytes()
            val ownNode = DkgNode.starter(instanceId, threshold.toUByte())
            val setupBytes = ownNode.setupBytes()
            val setupBase64 = Base64.encodeToString(setupBytes, Base64.URL_SAFE + Base64.NO_WRAP)

            val existingUser = auth.currentUser ?: throw RuntimeException("auth.currentUser is null?!")
            val ownClientId = existingUser.uid

            // JSON-compatible map for Firebase
            val inviteMap = mapOf(
                "type" to "create",
                "initiatorId" to ownClientId,
                "targetId" to otherClientId,
                "instanceIdBase64" to Base64.encodeToString(instanceIdBytes, Base64.URL_SAFE + Base64.NO_WRAP),
                "setupBase64" to setupBase64,
                "threshold" to threshold,
                "total" to total,
                "timestamp" to System.currentTimeMillis(),
                "status" to "pending"
            )

            database.child("invites").child(otherClientId).child(ownClientId).setValue(inviteMap)
                .addOnSuccessListener {
                    Toast.makeText(this@MainActivity, "Invite sent to $otherClientId", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "DKG invite sent to $otherClientId")

                    val inviteListener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val inviteMap = snapshot.value as? Map<*, *>
                            val invite = InviteData(
                                type = inviteMap?.get("type") as? String ?: "",
                                initiatorId = inviteMap?.get("initiatorId") as? String ?: "",
                                targetId = inviteMap?.get("targetId") as? String ?: "",
                                instanceIdBase64 = inviteMap?.get("instanceIdBase64") as? String ?: "",
                                setupBase64 = inviteMap?.get("setupBase64") as? String ?: "",
                                threshold = (inviteMap?.get("threshold") as? Long)?.toInt() ?: 0,
                                total = (inviteMap?.get("total") as? Long)?.toInt() ?: 0,
                                timestamp = inviteMap?.get("timestamp") as? Long ?: 0L,
                                status = inviteMap?.get("status") as? String ?: ""
                            )

                            if (invite.status == "accepted" && invite.setupBase64 != null) {
                                Log.d(TAG, "Invite accepted by $otherClientId! Proceeding with DKG...")
                                database.child("invites").child(otherClientId).child(ownClientId).removeEventListener(this)

                                try {
                                    // Update our node from responder's setup bytes (exchanges VKs implicitly)
                                    val responderSetupBytes = Base64.decode(invite.setupBase64, Base64.URL_SAFE + Base64.NO_WRAP)
                                    ownNode.updateFromBytes(responderSetupBytes)

                                    val instanceIdBase64 = Base64.encodeToString(instanceId.toBytes(), Base64.URL_SAFE + Base64.NO_WRAP)
                                    lifecycleScope.launch {
                                        performDKG(ownNode, instanceIdBase64, otherClientId)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to add party VKs and run DKG", e)
                                    DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                                }
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Invite listener cancelled", error.toException())
                            DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                        }
                    }

                    database.child("invites").child(otherClientId).child(ownClientId).addValueEventListener(inviteListener)

                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(120_000)
                        database.child("invites").child(otherClientId).child(ownClientId).removeValue().await()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send invite", e)
                    DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create DKG invite", e)
            DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
        }
    }

    private fun handleAcceptInvite(invite: InviteData) {
        lifecycleScope.launch {
            try {
                val instanceId = InstanceId.fromBytes(
                    Base64.decode(invite.instanceIdBase64!!, Base64.URL_SAFE + Base64.NO_WRAP)
                )
                val ownNode = DkgNode.fromSetupBytes(invite.toSetupBytes())

                val ownSetupBytes = ownNode.setupBytes()
                val ownSetupBase64 =
                    Base64.encodeToString(ownSetupBytes, Base64.URL_SAFE + Base64.NO_WRAP)

                val updateMap = mapOf(
                    "status" to "accepted",
                    "setupBase64" to ownSetupBase64,
                    "timestamp" to System.currentTimeMillis()
                )

                val existingUser =
                    auth.currentUser ?: throw RuntimeException("auth.currentUser is null?!")
                val ownClientId = existingUser.uid

                database.child("invites").child(ownClientId).child(invite.initiatorId!!)
                    .updateChildren(updateMap)
                    .addOnSuccessListener {
                        Log.d(TAG, "Invite accepted, VK sent back")
                        val instanceIdBase64 = Base64.encodeToString(
                            invite.toInstanceId().toBytes(),
                            Base64.URL_SAFE + Base64.NO_WRAP
                        )
                        lifecycleScope.launch {
                            performDKG(ownNode, instanceIdBase64, invite.initiatorId)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update invite", e)
                        DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Accept invite failed", e)
                DklsStatus.setStatus(DklsStatus.STATUS_KEYGEN_ERROR)
                Toast.makeText(this@MainActivity, "Failed to accept invite", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private suspend fun performDKG(node: DkgNode, instanceIdBase64: String, peerClientId: String) {
        try {
            Log.d(TAG, "Performing DKG with network...")

            val existingUser = auth.currentUser ?: throw RuntimeException("auth.currentUser null")
            val ownClientId = existingUser.uid

            // Create Firebase network interface
            val networkInterface = FirebaseNetworkInterface(
                database,
                instanceIdBase64,
                ownClientId,
                peerClientId
            )

            Log.d(TAG, "About to call node.doKeygen()...")
            dklsKeyshare = node.doKeygen(networkInterface)
            Log.d(TAG, "doKeygen() completed!")

            DklsStatus.setStatus(DklsStatus.STATUS_KEY_READY)
            Toast.makeText(this@MainActivity, "✅ 2-of-2 keyshare created!", Toast.LENGTH_LONG).show()
            Log.d(TAG, "DKG completed successfully with $peerClientId")

            // Cleanup DKG messages
            try {
                database.child("dkg_messages").child(instanceIdBase64).removeValue().await()
                Log.d(TAG, "Cleaned dkg_messages/$instanceIdBase64")
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup dkg_messages failed", e)
            }

            // Also clean specific invite if initiator
            val inviteRef = database.child("invites").child(peerClientId).child(ownClientId)
            try {
                inviteRef.removeValue().await()
                Log.d(TAG, "Cleaned invite $peerClientId/$ownClientId")
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup invite failed", e)
            }

        } catch (e: GeneralException) {
            DklsStatus.setStatus(DklsStatus.STATUS_KEYGEN_ERROR)
            Log.e(TAG, "DKLS Keygen error", e)
        } catch (e: Exception) {
            DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
            Log.e(TAG, "DKG failed", e)
        }
    }

    private fun startPresenceFlow(clientId: String) {
        val presenceRef = database.child("presence").child(clientId)

        // Update presence every 10 seconds
        lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                presenceRef.setValue(now)
                    .addOnSuccessListener {
                        //Log.d(TAG, "$clientId - Last Seen: $now")
                    }
                delay(10_000)
            }
        }
    }

    private fun setupUi(ownClientId: String) {
        setContent {
            FirebaseChatSimTheme {
                StatusScreen(
                    ownClientId = ownClientId,
                    database = database,
                    context = LocalContext.current,
                    dklsKeyshare = dklsKeyshare,
                    onAddDevice = ::handleAddDevice,
                    onAcceptInvite = ::handleAcceptInvite
                )
            }
        }
    }
}

fun String.copyToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("clientID", this))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun hexString(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it) }
}