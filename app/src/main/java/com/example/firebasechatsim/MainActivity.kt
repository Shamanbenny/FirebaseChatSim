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
import com.example.firebasechatsim.ui.theme.FirebaseChatSimTheme
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class MainActivity : ComponentActivity() {
    private lateinit var database: DatabaseReference
    private val TAG = "FirebaseChatSim"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Firebase
        database = FirebaseDatabase.getInstance().reference

        Log.d(TAG, "Firebase initialized!")
        Log.d(TAG, "Database URL: ${database.key}")

        // Test write to root (safe in test mode)
        database.child("status").setValue("Client connected at ${System.currentTimeMillis()}")
            .addOnSuccessListener { Log.d(TAG, "Test write SUCCESS") }
            .addOnFailureListener { Log.d(TAG, "Test write FAILED: ${it.message}}") }
    }
}