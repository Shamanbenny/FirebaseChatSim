package com.example.firebasechatsim

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
fun WatchlistScreen(
    ownClientId: String,
    database: DatabaseReference
) {
    var input by remember { mutableStateOf("") }
    var watchlist by remember { mutableStateOf(listOf<PresenceInfo>()) }

    // map clientId -> listener to avoid duplicates
    val listeners = remember { mutableStateMapOf<String, ValueEventListener>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Your clientId: $ownClientId")

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("clientId to watch") },
                modifier = Modifier
                    .fillMaxWidth(0.77f),
                singleLine = true,
                maxLines = 1
            )

            Button(
                onClick = {
                    val targetId = input.trim()
                    if (targetId.isNotEmpty() && !listeners.containsKey(targetId)) {
                        // Append entry to watchlist with unknown lastSeen (FOR NOW)
                        watchlist = watchlist + PresenceInfo(targetId, null)

                        val ref = database.child("presence").child(targetId)
                        val listener = object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val ts = snapshot.getValue(Long::class.java)
                                watchlist = watchlist.map {
                                    if (it.clientId == targetId) it.copy(lastSeen = ts) else it
                                }
                                Log.d("FirebaseChatSim", "${snapshot.key} - " +
                                        "Last Seen: ${snapshot.value}")
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Log.d("FirebaseChatSim", "Listener cancelled for " +
                                        "$targetId: ${error.message}")
                            }
                        }
                        ref.addValueEventListener(listener)
                        listeners[targetId] = listener
                        input = ""
                    }
                },
                modifier = Modifier.wrapContentWidth().padding(
                    horizontal = 2.dp
                )
            ) {
                Text("Add")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(watchlist) { item ->
                val statusText =
                    if (item.isOnline) "ONLINE" else "OFFLINE"
                val lastSeenText =
                    item.lastSeen?.toString() ?: "A long time ago..."

                Column {
                    Text(text = "clientId: ${item.clientId}")
                    Text(text = "Status: $statusText (Last seen=$lastSeenText)")
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private val TAG = "FirebaseChatSim"

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
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val clientId = result.user?.uid ?: "unknown"
                    Log.d(TAG, "Signed in anonymously. clientId = $clientId")
                    startPresenceFlow(clientId)
                    setupUi(clientId)
                }
                .addOnFailureListener { e ->
                    Log.d(TAG, "Anonymous sign-in FAILED: ${e.message}")
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
                    .addOnSuccessListener { Log.d(TAG, "$clientId - Last Seen: $now") }
                delay(10_000)
            }
        }
    }

    private fun setupUi(ownClientId: String) {
        setContent {
            FirebaseChatSimTheme {
                WatchlistScreen(
                    ownClientId = ownClientId,
                    database = database
                )
            }
        }
    }
}