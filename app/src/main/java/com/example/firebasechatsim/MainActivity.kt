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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.domerrors.NetworkError
import androidx.lifecycle.lifecycleScope
import com.example.firebasechatsim.ui.theme.FirebaseChatSimTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import uniffi.dkls.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
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
    val type: String = "",
    val initiatorId: String = "",
    val targetId: String = "",
    val instanceId: InstanceId,
    val partyVKs: List<NodeVerifyingKey> = emptyList(),
    val threshold: Int = 0,
    val total: Int = 0,
    val timestamp: Long = 0L,
    val status: String = ""
)

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
    onAddDevice: (String) -> Unit,  // targetClientId
    onAcceptInvite: (InviteData) -> Unit  // InviteData
) {
    var inputClientId by remember { mutableStateOf("") }
    val dklsStatus by remember { derivedStateOf { DklsStatus.getStatus() } }
    val coroutineScope = rememberCoroutineScope()

    // Pending invites state
    var pendingInvites by remember { mutableStateOf<List<InviteData>>(emptyList()) }

    // Listen for incoming invites
    LaunchedEffect(ownClientId) {
        database.child("invites").child(ownClientId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val invites = snapshot.children.mapNotNull { child ->
                        child.getValue(InviteData::class.java)
                    }
                    pendingInvites = invites.filter { it.status == "pending" }
                }
                override fun onCancelled(error: DatabaseError) {}
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
                Text(text = "DKLS Status", style = MaterialTheme.typography.headlineSmall,
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
                                        text = "From: ${invite.initiatorId.take(8)}...",
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
                                        onClick = { onAcceptInvite(invite) },
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
    private var dklsKeyshare: Keyshare? = null

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
                    sendDKGInvite(otherClientId, 2, 2)
                }
                .addOnFailureListener {
                    Toast.makeText(this@MainActivity, "Presence check failed", Toast.LENGTH_SHORT).show()
                    DklsStatus.setStatus(DklsStatus.STATUS_NO_KEY)
                }
        }
    }

    private fun sendDKGInvite(otherClientId: String, threshold: Int, total: Int) {
        lifecycleScope.launch {
            try {
                val instanceId = InstanceId.fromEntropy()
                val ownNode = DkgNode.starter(instanceId, threshold.toUByte())
                val ownVK = ownNode.myVk().toString()

                val existingUser = auth.currentUser ?: throw RuntimeException("auth.currentUser is null?!")
                val ownClientId = existingUser.uid

                val inviteData = mapOf(
                    "type" to "create",
                    "initiatorId" to ownClientId,
                    "targetId" to otherClientId,
                    "instanceId" to instanceId,
                    "partyVKs" to listOf(ownVK),
                    "threshold" to threshold,
                    "total" to total,
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "pending"
                )

                // TODO: Fix the call to Firebase's Realtime Database's setValue such that it works with the intended InviteData class.
                //      Currently, nothing is being reflected as update on the Firebase's Realtime Database due to usage of non-primitive datatype
                database.child("invites").child(otherClientId).child(ownClientId).setValue(inviteData)
                    .addOnSuccessListener {
                        Toast.makeText(this@MainActivity, "Invite sent to $otherClientId", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "DKG invite sent to $otherClientId (instance: $instanceId)")

                        val inviteListener = object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val invite = snapshot.getValue(InviteData::class.java)
                                if (invite?.status == "accepted" && invite.partyVKs.size == 2) {
                                    Log.d(TAG, "Invite accepted by $otherClientId! Proceeding with DKG...")
                                    database.child("invites").child(otherClientId).child(ownClientId).removeEventListener(this)

                                    // Add target party's VK and run DKG
                                    val targetVKs = invite.partyVKs.filter { it != ownNode.myVk() }
                                    if (targetVKs.isEmpty()) {
                                        try {
                                            targetVKs.forEach { ownNode.addParty(it) }
                                            performDKG(ownNode)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to add party VK and run DKG", e)
                                            DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                                        }
                                    }
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Log.e(TAG, "Invite listener cancelled", error.toException())
                                DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                            }
                        }

                        // Listen for changes to this specific invite
                        database.child("invites").child(otherClientId).child(ownClientId)
                            .addValueEventListener(inviteListener)

                        // Auto-cleanup after 2 minutes timeout
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(120_000) // 2 minutes
                            database.child("invites").child(otherClientId).child(ownClientId)
                                .removeEventListener(inviteListener)
                            Log.d(TAG, "DKG invite timeout for $otherClientId")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send invite", e)
                        DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                    }

                // Continue
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create DKG invite", e)
                DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
            }
        }
    }

    private fun handleAcceptInvite(invite: InviteData) {
        lifecycleScope.launch {
            try {
                val ownNode = DkgNode(
                    instance = invite.instanceId,
                    threshold = invite.threshold.toUByte(),
                    partyVk = invite.partyVKs
                )
                val ownVK = ownNode.myVk().toString()
                val allVKs = invite.partyVKs + ownVK

                // Update invite with our VK and mark accepted
                val updateData = mapOf(
                    "status" to "accepted",
                    "partyVKs" to allVKs,
                    "timestamp" to System.currentTimeMillis()
                )

                val existingUser = auth.currentUser ?: throw RuntimeException("auth.currentUser is null?!")
                val ownClientId = existingUser.uid

                database.child("invites").child(ownClientId).child(invite.initiatorId).updateChildren(updateData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Invite accepted, VK sent back")
                        performDKG(ownNode)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update invite", e)
                        DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Accept invite failed", e)
                DklsStatus.setStatus(DklsStatus.STATUS_KEYGEN_ERROR)
                Toast.makeText(this@MainActivity, "Failed to accept invite", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performDKG(node: DkgNode) {
        lifecycleScope.launch {
            try {
                val runner = DkgRunner()
                runner.initializeTokioRuntime()
                dklsKeyshare = runner.run(node)
                DklsStatus.setStatus(DklsStatus.STATUS_KEY_READY)
                Toast.makeText(this@MainActivity, "✅ 2-of-2 keyshare created!", Toast.LENGTH_LONG).show()
                Log.d(TAG, "DKG completed successfully")
            } catch (e: KeygenException) {
                DklsStatus.setStatus(DklsStatus.STATUS_KEYGEN_ERROR)
                Log.e(TAG, "DKLS Keygen error", e)
            } catch (e: NetworkException) {
                DklsStatus.setStatus(DklsStatus.STATUS_NETWORK_ERROR)
                Log.e(TAG, "DKLS Network error", e)
            } catch (e: Exception) {
                DklsStatus.setStatus(DklsStatus.STATUS_ERROR)
                Log.e(TAG, "DKLS error", e)
            }
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
                        Log.d(TAG, "$clientId - Last Seen: $now")
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