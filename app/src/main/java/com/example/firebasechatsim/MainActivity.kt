package com.example.firebasechatsim

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.example.firebasechatsim.ui.theme.FirebaseChatSimTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var database: DatabaseReference
    private val TAG = "FirebaseChatSim"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference
        val statusRef = database.child("status")
        val clientId = "Client_${System.currentTimeMillis() % 1000}"  // Unique clientId

        Log.d(TAG, "Firebase initialized!")
        Log.d(TAG, "\uD83D\uDD17 Watching /status for live updates...")

        // REAL-TIME LISTENER - Core Firebase Feature
        statusRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val statusValue = snapshot.value
                Log.d (TAG, "LIVE UPDATE: /status = $statusValue")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.d(TAG, "Listener cancelled: ${error.message}")
            }
        })

        // Send unique status every 10 seconds
        lifecycleScope.launch {
            while (true) {
                delay(10000)
                statusRef.setValue("Online: $clientId at ${System.currentTimeMillis()}")
                    .addOnSuccessListener { Log.d(TAG, "Sent: $clientId") }
            }
        }
    }
}