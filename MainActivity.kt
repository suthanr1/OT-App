package com.suthan.otmanagement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val SUPABASE_URL = "https://vosgviopuhieslzajleb.supabase.co"
private const val API_BASE = "$SUPABASE_URL/functions/v1"
// IMPORTANT: do not put a Supabase secret/service_role key here.
// The publishable key is intentionally left as a placeholder.
private const val SUPABASE_PUBLISHABLE_KEY = "PASTE_YOUR_SUPABASE_PUBLISHABLE_KEY_HERE"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OTManagementApp() }
    }
}

@Composable
fun OTManagementApp() {
    var loginId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (!loggedIn) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("OT Management", style = MaterialTheme.typography.headlineMedium)
                    Text("Online + Offline Sync")
                    OutlinedTextField(loginId, { loginId = it }, label = { Text("Login ID") })
                    OutlinedTextField(password, { password = it }, label = { Text("Password") })
                    Button(
                        onClick = { loggedIn = loginId.isNotBlank() && password.isNotBlank() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Login") }
                    Text("Employee Login ID = Employee Name • Password = Employee ID")
                }
            } else {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("OT Management", style = MaterialTheme.typography.headlineMedium)
                    Text("Login UI is ready. Supabase API + offline sync modules are the next integration layer.")
                    Button(onClick = { loggedIn = false }) { Text("Logout") }
                }
            }
        }
    }
}
