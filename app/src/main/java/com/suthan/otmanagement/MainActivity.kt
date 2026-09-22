package com.suthan.otmanagement

import android.app.DatePickerDialog
import androidx.activity.compose.setContent
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable

private const val STORE = "ot_offline_store_v1"
private const val DEFAULT_RATE = 75.0

private data class Employee(
    val id: String,
    val name: String,
    val active: Boolean = true,
    val password: String = id
)
private data class OtEntry(
    val uid: String,
    val employeeId: String,
    val date: String,
    val from: String,
    val to: String,
    val hours: Double,
    val rate: Double,
    val multiplier: Int,
    val amount: Double
)
private data class AppData(
    val adminPassword: String,
    val rate: Double,
    val employees: List<Employee>,
    val entries: List<OtEntry>,
    val sessionRole: String = "",
    val sessionEmployeeId: String = "",
    val darkMode: Boolean = false
)

private object Store {
    private fun prefs(c: Context) = c.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun load(c: Context): AppData {
        val p = prefs(c)
        val employees = mutableListOf<Employee>()
        val ea = runCatching { JSONArray(p.getString("employees", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until ea.length()) {
            runCatching {
                val o = ea.getJSONObject(i)
                val id = o.optString("id").trim()
                val name = o.optString("name").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    employees += Employee(id, name, o.optBoolean("active", true), o.optString("password", id))
                }
            }
        }

        val entries = mutableListOf<OtEntry>()
        val oa = runCatching { JSONArray(p.getString("entries", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until oa.length()) {
            runCatching {
                val o = oa.getJSONObject(i)
                val uid = o.optString("uid")
                val employeeId = o.optString("employeeId")
                val date = o.optString("date")
                if (employeeId.isNotEmpty() && date.isNotEmpty()) {
                    entries += OtEntry(
                        uid, employeeId, date,
                        o.optString("from"), o.optString("to"), o.optDouble("hours"),
                        o.optDouble("rate", DEFAULT_RATE), o.optInt("multiplier", 1),
                        o.optDouble("amount")
                    )
                }
            }
        }

        return AppData(
            p.getString("adminPassword", "admin123") ?: "admin123",
            p.getFloat("rate", DEFAULT_RATE.toFloat()).toDouble(),
            employees, entries,
            p.getString("sessionRole", "") ?: "",
            p.getString("sessionEmployeeId", "") ?: "",
            p.getBoolean("darkMode", false)
        )
    }

    fun save(c: Context, d: AppData) {
        val ea = JSONArray()
        d.employees.forEach { e -> ea.put(JSONObject().apply { put("id", e.id); put("name", e.name); put("active", e.active); put("password", e.password) }) }
        val oa = JSONArray()
        d.entries.forEach { e ->
            oa.put(JSONObject().apply {
                put("uid", e.uid); put("employeeId", e.employeeId); put("date", e.date); put("from", e.from); put("to", e.to)
                put("hours", e.hours); put("rate", e.rate); put("multiplier", e.multiplier); put("amount", e.amount)
            })
        }
        prefs(c).edit()
            .putString("adminPassword", d.adminPassword)
            .putFloat("rate", d.rate.toFloat())
            .putString("employees", ea.toString())
            .putString("entries", oa.toString())
            .putString("sessionRole", d.sessionRole)
            .putString("sessionEmployeeId", d.sessionEmployeeId)
            .putBoolean("darkMode", d.darkMode)
            .apply()
    }

    fun clear(c: Context) { prefs(c).edit().clear().apply() }
}

private fun fmt2(x: Double) = String.format(Locale.US, "%.2f", x)
private fun money(x: Double) = "₹${fmt2(x)}"
private fun parseMinutes(s: String): Int {
    val p = s.split(":")
    return if (p.size == 2) p[0].toIntOrNull()?.times(60)?.plus(p[1].toIntOrNull() ?: 0) ?: 0 else 0
}
private fun calcHours(from: String, to: String): Double {
    var a = parseMinutes(from); var b = parseMinutes(to)
    if (b < a) b += 24 * 60
    return (b - a) / 60.0
}

private fun formatTime12(time24: String): String {
    val p = time24.split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: 0
    val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, h)
    cal.set(Calendar.MINUTE, m)
    return SimpleDateFormat("h:mm a", Locale.ENGLISH).format(cal.time)
}
private fun cycleKey(date: String): String {
    val c = Calendar.getInstance()
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) }.getOrNull() ?: return date.take(7)
    c.time = d
    if (c.get(Calendar.DAY_OF_MONTH) >= 26) c.add(Calendar.MONTH, 1)
    return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}
private fun cycleLabel(key: String): String {
    val p = key.split("-"); if (p.size != 2) return key
    val c = Calendar.getInstance(); c.set(p[0].toInt(), p[1].toInt() - 1, 1)
    return SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(c.time).uppercase(Locale.ENGLISH)
}
private fun cycleRange(key: String): String {
    val p = key.split("-"); if (p.size != 2) return ""
    val end = Calendar.getInstance(); end.set(p[0].toInt(), p[1].toInt() - 1, 25)
    val start = end.clone() as Calendar; start.add(Calendar.MONTH, -1); start.set(Calendar.DAY_OF_MONTH, 26)
    val f = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    return "${f.format(start.time)} → ${f.format(end.time)}"
}
private fun currentDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
private fun isSunday(date: String): Boolean {
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) }.getOrNull() ?: return false
    val c = Calendar.getInstance(); c.time = d; return c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
}
private fun folderKeys(d: AppData): List<String> {
    val keys = linkedSetOf<String>()
    val base = Calendar.getInstance()
    repeat(13) { i ->
        val c = base.clone() as Calendar; c.add(Calendar.MONTH, i)
        val date = String.format(Locale.US, "%04d-%02d-01", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        keys += cycleKey(date)
    }
    d.entries.forEach { keys += cycleKey(it.date) }
    return keys.sortedDescending()
}

@Serializable
private data class CloudProfile(
    val id: String,
    val employee_id: String,
    val employee_name: String,
    val role: String,
    val hourly_rate: Double = DEFAULT_RATE,
    val is_active: Boolean = true
)

private suspend fun cloudLogin(login: String, password: String, role: String): Result<CloudProfile> = runCatching {
    val email = if (role == "admin") {
        "admin@ottrack.local"
    } else {
        val safe = login.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), ".")
            .trim('.')
        require(safe.isNotEmpty()) { "Enter employee name" }
        "$safe@ottrack.local"
    }

    supabase.auth.signInWith(Email) {
        this.email = email
        this.password = password
    }

    val userId = supabase.auth.currentUserOrNull()?.id
        ?: error("Supabase login succeeded but user session was not found")

    supabase.from("profiles")
        .select {
            filter {
                eq("id", userId)
            }
        }
        .decodeSingle<CloudProfile>()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OtApp() }
    }
}

@Composable
private fun OtApp() {
    val context = LocalContext.current
    var data by remember { mutableStateOf(Store.load(context)) }
    fun update(next: AppData) { data = next; Store.save(context, next) }

    val lightColors = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF2563EB),
        onPrimary = androidx.compose.ui.graphics.Color.White,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE8FF),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF0B2B5A),
        secondary = androidx.compose.ui.graphics.Color(0xFF0F4C81),
        background = androidx.compose.ui.graphics.Color(0xFFF5F8FC),
        onBackground = androidx.compose.ui.graphics.Color(0xFF172033),
        surface = androidx.compose.ui.graphics.Color.White,
        onSurface = androidx.compose.ui.graphics.Color(0xFF172033),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE9EEF6),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF5B667A),
        outline = androidx.compose.ui.graphics.Color(0xFFD3DBE8)
    )
    val darkColors = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF76A7FF),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF06295C),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF123B73),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE8FF),
        secondary = androidx.compose.ui.graphics.Color(0xFF7DB8F2),
        background = androidx.compose.ui.graphics.Color(0xFF0B1220),
        onBackground = androidx.compose.ui.graphics.Color(0xFFE7EDF7),
        surface = androidx.compose.ui.graphics.Color(0xFF111B2E),
        onSurface = androidx.compose.ui.graphics.Color(0xFFE7EDF7),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1D2940),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFAAB7CA),
        outline = androidx.compose.ui.graphics.Color(0xFF33415A)
    )
    MaterialTheme(
        colorScheme = if (data.darkMode) darkColors else lightColors,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        )
    ) {
        when (data.sessionRole) {
            "admin" -> AdminApp(data, ::update)
            "employee" -> EmployeeApp(data, ::update)
            else -> LoginScreen { role, login, password ->
                cloudLogin(login, password, role).map { profile ->
                    val isExpectedRole = profile.role.equals(role, ignoreCase = true)
                    if (!profile.is_active || !isExpectedRole) {
                        runCatching { supabase.auth.signOut() }
                        error("Account is inactive or role is not allowed")
                    }

                    if (profile.role.equals("admin", ignoreCase = true)) {
                        update(
                            data.copy(
                                sessionRole = "admin",
                                sessionEmployeeId = "",
                                rate = profile.hourly_rate
                            )
                        )
                    } else {
                        val employee = Employee(
                            id = profile.employee_id,
                            name = profile.employee_name,
                            active = profile.is_active,
                            password = ""
                        )
                        update(
                            data.copy(
                                employees = (data.employees.filterNot { it.id == employee.id } + employee),
                                sessionRole = "employee",
                                sessionEmployeeId = employee.id,
                                rate = profile.hourly_rate
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(onLogin: suspend (String, String, String) -> Result<Unit>) {
    var role by remember { mutableStateOf("employee") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painter = painterResource(id = com.suthan.otmanagement.R.drawable.ot_track_logo),
                contentDescription = "OT Track",
                modifier = Modifier.size(150.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("OT Track", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Online OT Management", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "employee", onClick = { role = "employee" }, label = { Text("Employee") })
                FilterChip(selected = role == "admin", onClick = { role = "admin" }, label = { Text("Admin") })
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(login, { login = it; error = "" }, label = { Text(if (role == "admin") "Admin Login ID" else "Employee Name") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(password, { password = it; error = "" }, label = { Text(if (role == "admin") "Admin Password" else "Employee ID / Password") }, singleLine = true)
            if (error.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (login.isBlank() || password.isBlank()) {
                        error = "Enter login details"
                        return@Button
                    }
                    loading = true
                    error = ""
                    scope.launch {
                        val result = runCatching { onLogin(role, login, password) }.getOrElse { Result.failure(it) }
                        loading = false
                        result.exceptionOrNull()?.let { ex ->
                            error = ex.message?.takeIf { it.isNotBlank() } ?: ex::class.simpleName ?: "Login failed"
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)
            ) {
                Text(if (loading) "Connecting..." else "Login")
            }
            Spacer(Modifier.height(12.dp))
            Text("No employee registration. Admin creates employee accounts.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmployeeApp(data: AppData, update: (AppData) -> Unit) {
    val employee = data.employees.firstOrNull { it.id == data.sessionEmployeeId }
    if (employee == null) { update(data.copy(sessionRole = "")); return }
    var tab by remember { mutableStateOf(0) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.List, null) }, label = { Text("My Logs") })
            NavigationBarItem(tab == 2, { tab = 2; selectedFolder = null }, icon = { Icon(Icons.Default.Assessment, null) }, label = { Text("Reports") })
            NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
        }
    }) { pad ->
        when (tab) {
            0 -> EmployeeHome(data, employee, update, pad)
            1 -> EmployeeRecords(data, employee.id, cycleKey(currentDate()), "My Logs", update, pad)
            2 -> EmployeeReports(data, employee.id, selectedFolder, { selectedFolder = it }, update, pad)
            else -> EmployeeSettings(data, employee, update, pad)
        }
    }
}

@Composable
private fun OtTrackBrand(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = com.suthan.otmanagement.R.drawable.ot_track_logo),
            contentDescription = "OT Track",
            modifier = Modifier.size(if (compact) 44.dp else 54.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text("OT Track", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (!compact) Text("Track Today • Plan Tomorrow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmployeeHome(data: AppData, employee: Employee, update: (AppData) -> Unit, pad: PaddingValues) {
    var showAdd by remember { mutableStateOf(false) }
    val current = cycleKey(currentDate())
    val own = data.entries.filter { it.employeeId == employee.id && cycleKey(it.date) == current }
    LazyColumn(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OtTrackBrand()
                TextButton(onClick = { update(data.copy(sessionRole = "", sessionEmployeeId = "")) }) {
                    Text("Logout", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Welcome back", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(employee.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your current overtime summary", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CURRENT OT CYCLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .82f))
                    Text(cycleLabel(current), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    Text(cycleRange(current), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .86f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = .12f))) {
                            Column(Modifier.padding(14.dp)) {
                                Text("OT Hours", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .82f))
                                Text(fmt2(own.sumOf { it.hours }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = .12f))) {
                            Column(Modifier.padding(14.dp)) {
                                Text("OT Amount", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .82f))
                                Text(money(own.sumOf { it.amount }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { showAdd = true },
                Modifier.fillMaxWidth().height(52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add OT Entry", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("OT Rate", fontWeight = FontWeight.SemiBold)
                        Text("Admin controlled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${money(data.rate)}/hour", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Text("Recent OT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(own.sortedByDescending { it.date }.take(5)) {
            OtRow(it, null, onDelete = { update(data.copy(entries = data.entries.filterNot { x -> x.uid == it.uid })) })
        }
        if (own.isEmpty()) item { Text("No OT entries in this cycle.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (showAdd) AddOtDialog(data, employee, { update(it) }, { showAdd = false })
}

@Composable
private fun EmployeeRecords(data: AppData, employeeId: String, key: String, title: String, update: (AppData) -> Unit, pad: PaddingValues) {
    val rows = data.entries.filter { it.employeeId == employeeId && cycleKey(it.date) == key }.sortedByDescending { it.date }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${cycleLabel(key)} • ${cycleRange(key)}") }
        item { LogSummaryCard(rows) }
        items(rows) { row -> OtRow(row, null, onDelete = { update(data.copy(entries = data.entries.filterNot { x -> x.uid == row.uid })) }) }
        if (rows.isEmpty()) item { Text("No records in this monthly cycle.") }
    }
}

@Composable
private fun EmployeeReports(data: AppData, employeeId: String, selected: String?, onSelect: (String?) -> Unit, update: (AppData) -> Unit, pad: PaddingValues) {
    if (selected != null) {
        val rows = data.entries.filter { it.employeeId == employeeId && cycleKey(it.date) == selected }.sortedByDescending { it.date }
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            TextButton(onClick = { onSelect(null) }) { Text("← All Reports") }
            Text("${cycleLabel(selected)} Report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(cycleRange(selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LogSummaryCard(rows)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows) { r -> OtRow(r, null, onDelete = { update(data.copy(entries = data.entries.filterNot { x -> x.uid == r.uid })) }) }
                if (rows.isEmpty()) item { Text("No OT entries in this monthly cycle.") }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Reports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Monthly folders • 26th → 25th", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(folderKeys(data)) { key ->
            val rows = data.entries.filter { it.employeeId == employeeId && cycleKey(it.date) == key }
            Card(Modifier.fillMaxWidth().clickable { onSelect(key) }) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text(cycleLabel(key), fontWeight = FontWeight.Bold); Text(cycleRange(key), color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("${rows.size} OT") } }
        }
    }
}

@Composable
private fun EmployeeSettings(data: AppData, employee: Employee, update: (AppData) -> Unit, pad: PaddingValues) {
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(context, uri, data)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            readBackup(context, uri)?.let { restored ->
                update(restored.copy(sessionRole = "employee", sessionEmployeeId = employee.id, darkMode = data.darkMode))
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("My Profile", fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(employee.name); Text("Employee ID: ${employee.id}", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { OutlinedButton(onClick = { showPassword = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(6.dp)); Text("Change Password") } }
        item { OutlinedButton(onClick = { update(data.copy(darkMode = !data.darkMode)) }, Modifier.fillMaxWidth()) { Icon(if (data.darkMode) Icons.Default.LightMode else Icons.Default.DarkMode, null); Spacer(Modifier.width(6.dp)); Text(if (data.darkMode) "Light Mode" else "Dark Mode") } }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup & Restore", fontWeight = FontWeight.Bold)
                    Text("Keep your OT data safe before uninstalling or changing phones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { backup.launch("OT-Track-Backup.json") }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Backup, null); Spacer(Modifier.width(6.dp)); Text("Backup Offline Data")
                    }
                    OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/json", "*/*")) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Restore, null); Spacer(Modifier.width(6.dp)); Text("Restore Offline Data")
                    }
                }
            }
        }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("About OT Track", fontWeight = FontWeight.Bold); Text("Online OT Management", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { OutlinedButton(onClick = { update(data.copy(sessionRole = "", sessionEmployeeId = "")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(6.dp)); Text("Logout") } }
    }
    if (showPassword) EmployeePasswordDialog(employee, data, update) { showPassword = false }
}

@Composable
private fun EmployeeFolders(data: AppData, employeeId: String, selected: String?, onSelect: (String?) -> Unit, update: (AppData) -> Unit, pad: PaddingValues) {
    if (selected != null) {
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            TextButton(onClick = { onSelect(null) }) { Text("← All Monthly Folders") }
            EmployeeRecords(data, employeeId, selected, cycleLabel(selected), update, PaddingValues(0.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Monthly Folders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("26th → 25th cycle") }
        items(folderKeys(data)) { key ->
            val rows = data.entries.filter { it.employeeId == employeeId && cycleKey(it.date) == key }
            Card(Modifier.fillMaxWidth().clickable { onSelect(key) }) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(cycleLabel(key), fontWeight = FontWeight.Bold); Text(cycleRange(key)) }; Text("${rows.size} OT") } }
        }
    }
}

@Composable
private fun AdminApp(data: AppData, update: (AppData) -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showEmployee by remember { mutableStateOf(false) }
    var showRate by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) writeCsv(context, uri, data) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) writeBackup(context, uri, data) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readBackup(context, uri)?.let(update) }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Dashboard, null) }, label = { Text("Dashboard") })
            NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.People, null) }, label = { Text("Employees") })
            NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Monthly") })
            NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
        }
    }) { pad ->
        when (tab) {
            0 -> AdminDashboard(data, update, pad, { showEmployee = true }, { showRate = true }, { export.launch("OT-Report.csv") })
            1 -> AdminEmployees(data, update, pad, { showEmployee = true })
            2 -> AdminMonthly(data, update, selectedFolder, { selectedFolder = it }, pad)
            else -> AdminSettings(data, update, pad, { showRate = true }, { showPassword = true }, { backup.launch("OT-Backup.json") }, { restore.launch(arrayOf("application/json")) }, { Store.clear(context); update(AppData("admin123", DEFAULT_RATE, emptyList(), emptyList())) })
        }
                AdminDialogs(
            data = data,
            update = update,
            showEmployee = showEmployee,
            showRate = showRate,
            showPassword = showPassword,
            closeEmployee = { showEmployee = false },
            closeRate = { showRate = false },
            closePassword = { showPassword = false }
        )
    }
}
@Composable
private fun AdminDialogs(
    data: AppData,
    update: (AppData) -> Unit,
    showEmployee: Boolean,
    showRate: Boolean,
    showPassword: Boolean,
    closeEmployee: () -> Unit,
    closeRate: () -> Unit,
    closePassword: () -> Unit
) {
    if (showEmployee) {
        EmployeeDialog(
            data = data,
            update = update,
            close = closeEmployee
        )
    }

    if (showRate) {
        RateDialog(
            data = data,
            update = update,
            close = closeRate
        )
    }

    if (showPassword) {
        PasswordDialog(
            data = data,
            update = update,
            close = closePassword
        )
    }
}

@Composable
private fun AdminDashboard(data: AppData, update: (AppData) -> Unit, pad: PaddingValues, add: () -> Unit, rate: () -> Unit, export: () -> Unit) {
    val key = cycleKey(currentDate()); val rows = data.entries.filter { cycleKey(it.date) == key }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Column { Text("Admin Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(cycleRange(key)) }; TextButton(onClick = { update(data.copy(sessionRole = "", sessionEmployeeId = "")) }) { Text("Logout") } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatCard("Employees", data.employees.count { it.active }.toString(), Modifier.weight(1f)); StatCard("OT Hours", fmt2(rows.sumOf { it.hours }), Modifier.weight(1f)); StatCard("OT Amount", money(rows.sumOf { it.amount }), Modifier.weight(1f)) } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("OT Rate – Admin Only", fontWeight = FontWeight.Bold); Text(money(data.rate) + "/hour"); Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = rate) { Text("Change Rate") } } } }
        item { Button(onClick = add, Modifier.fillMaxWidth()) { Text("Add Employee") } }
        item { OutlinedButton(onClick = export, Modifier.fillMaxWidth()) { Text("Export All OT CSV") } }
        item { Text("Current Cycle Records", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(rows.sortedByDescending { it.date }) { r -> OtRow(r, data.employees.firstOrNull { it.id == r.employeeId }?.name, onDelete = { update(data.copy(entries = data.entries.filterNot { x -> x.uid == r.uid })) }) }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier) { Card(modifier) { Column(Modifier.padding(12.dp)) { Text(title, style = MaterialTheme.typography.labelMedium); Text(value, fontWeight = FontWeight.Bold) } } }

@Composable
private fun AdminEmployees(data: AppData, update: (AppData) -> Unit, pad: PaddingValues, add: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Employee Management", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Button(onClick = add) { Text("+ Add") } } }
        items(data.employees.sortedBy { it.name.lowercase() }) { e ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text(e.name, fontWeight = FontWeight.Bold); Text("ID: ${e.id}"); Text(if (e.active) "Active" else "Disabled") } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { update(data.copy(employees = data.employees.map { if (it.id == e.id) it.copy(active = !it.active) else it })) }) { Text(if (e.active) "Disable" else "Enable") }
                    OutlinedButton(onClick = { update(data.copy(employees = data.employees.filterNot { it.id == e.id })) }) { Text("Delete") }
                }
            } }
        }
        if (data.employees.isEmpty()) item { Text("No employees yet.") }
    }
}

@Composable
private fun AdminMonthly(data: AppData, update: (AppData) -> Unit, selected: String?, onSelect: (String?) -> Unit, pad: PaddingValues) {
    if (selected != null) {
        val rows = data.entries.filter { cycleKey(it.date) == selected }.sortedByDescending { it.date }
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            TextButton(onClick = { onSelect(null) }) { Text("← All Monthly Folders") }
            Text(cycleLabel(selected), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(cycleRange(selected)); Spacer(Modifier.height(10.dp)); LogSummaryCard(rows)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(rows) { r -> OtRow(r, data.employees.firstOrNull { it.id == r.employeeId }?.name, onDelete = { update(data.copy(entries = data.entries.filterNot { x -> x.uid == r.uid })) }) } }
        }
    } else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Monthly Folders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Admin: all employee records") }
        items(folderKeys(data)) { key ->
            val rows = data.entries.filter { cycleKey(it.date) == key }
            Card(Modifier.fillMaxWidth().clickable { onSelect(key) }) { Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween) { Column { Text(cycleLabel(key), fontWeight = FontWeight.Bold); Text(cycleRange(key)) }; Column(horizontalAlignment = Alignment.End) { Text("${rows.size} entries"); Text(money(rows.sumOf { it.amount })) } } }
        }
    }
}

@Composable
private fun AdminSettings(data: AppData, update: (AppData) -> Unit, pad: PaddingValues, rate: () -> Unit, password: () -> Unit, backup: () -> Unit, restore: () -> Unit, clear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("OT Rate", fontWeight = FontWeight.Bold); Text("${money(data.rate)}/hour"); Button(onClick = rate) { Text("Change OT Rate") } } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Admin Password", fontWeight = FontWeight.Bold); Button(onClick = password) { Text("Change Admin Password") } } } }
        item { Button(onClick = backup, Modifier.fillMaxWidth()) { Text("Backup Offline Data") } }
        item { OutlinedButton(onClick = restore, Modifier.fillMaxWidth()) { Text("Restore Offline Data") } }
        item { OutlinedButton(onClick = clear, Modifier.fillMaxWidth()) { Text("Clear Device Data") } }
        item { Text("Cloud sync enabled. Offline backup is also available.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LogSummaryCard(rows: List<OtEntry>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TOTAL HOURS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmt2(rows.sumOf { it.hours }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TOTAL AMOUNT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(rows.sumOf { it.amount }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun OtRow(row: OtEntry, employeeName: String?, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                if (employeeName != null) Text(employeeName, fontWeight = FontWeight.Bold)
                Text("${formatDateDisplay(row.date)} • ${dayName(row.date)}", fontWeight = FontWeight.SemiBold)
                Text("${formatTime12(row.from)} → ${formatTime12(row.to)} • ${fmt2(row.hours)} Hrs", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (row.multiplier == 2) "Double 2×" else "Normal 1×")
            Text(money(row.amount), fontWeight = FontWeight.Bold)
        }
    } }
}

private fun formatDateDisplay(date: String): String {
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) }.getOrNull() ?: return date
    return SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(d)
}

private fun dayName(date: String): String {
    val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) }.getOrNull() ?: return ""
    return SimpleDateFormat("EEEE", Locale.ENGLISH).format(d)
}

@Composable
private fun AddOtDialog(data: AppData, employee: Employee, onSave: (AppData) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(currentDate()) }
    var from by remember { mutableStateOf("18:00") }
    var to by remember { mutableStateOf("20:00") }
    var multiplier by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Add OT Entry") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val c = Calendar.getInstance(); DatePickerDialog(context, { _, y, m, d -> date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text("Date: $date") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { timeDialog(context, from) { from = it } }) { Text("From ${formatTime12(from)}") }
                OutlinedButton(onClick = { timeDialog(context, to) { to = it } }) { Text("To ${formatTime12(to)}") }
            }
            val hours = calcHours(from, to)
            Text("OT Hours: ${fmt2(hours)}")
            if (isSunday(date)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(multiplier == 1, { multiplier = 1 }, label = { Text("Normal – 1×") })
                    FilterChip(multiplier == 2, { multiplier = 2 }, label = { Text("Double – 2×") })
                }
            } else { multiplier = 1; Text("Sunday option appears only on Sunday.") }
            Text("Amount: ${money(hours * data.rate * multiplier)}")
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = {
        val hours = calcHours(from, to)
        if (hours <= 0) { error = "OT time must be greater than 0"; return@Button }
        if (data.entries.any { it.employeeId == employee.id && it.date == date }) { error = "Only one OT entry is allowed for this date"; return@Button }
        val m = if (isSunday(date)) multiplier else 1
        val row = OtEntry("${employee.id}_${date}", employee.id, date, from, to, hours, data.rate, m, hours * data.rate * m)
        onSave(data.copy(entries = data.entries + row)); close()
    }) { Text("Add OT") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

private fun timeDialog(context: Context, initial: String, onPick: (String) -> Unit) {
    val p = initial.split(":"); val h = p.getOrNull(0)?.toIntOrNull() ?: 18; val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    TimePickerDialog(context, { _, hh, mm -> onPick(String.format(Locale.US, "%02d:%02d", hh, mm)) }, h, m, false).show()
}

@Composable
private fun EmployeePasswordDialog(employee: Employee, data: AppData, update: (AppData) -> Unit, close: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Change Password") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, { value = it }, label = { Text("New password") }, singleLine = true)
        OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") }, singleLine = true)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = {
        if (value.length < 4) error = "Minimum 4 characters"
        else if (value != confirm) error = "Passwords do not match"
        else { update(data.copy(employees = data.employees.map { if (it.id == employee.id) it.copy(password = value) else it })); close() }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun EmployeeDialog(data: AppData, update: (AppData) -> Unit, close: () -> Unit) {
    var name by remember { mutableStateOf("") }; var id by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Create Employee") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Employee Name") }, singleLine = true)
        OutlinedTextField(id, { id = it }, label = { Text("Employee ID / Password") }, singleLine = true)
        Text("Employee login ID = name; initial password = employee ID")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = {
        if (name.trim().isEmpty() || id.trim().isEmpty()) { error = "Enter name and ID"; return@Button }
        if (data.employees.any { it.id == id.trim() }) { error = "Employee ID already exists"; return@Button }
        update(data.copy(employees = data.employees + Employee(id.trim(), name.trim(), true, id.trim()))); close()
    }) { Text("Create") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun RateDialog(data: AppData, update: (AppData) -> Unit, close: () -> Unit) {
    var value by remember { mutableStateOf(fmt2(data.rate)) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Change OT Rate") }, text = { Column { OutlinedTextField(value, { value = it }, label = { Text("₹ per hour") }, singleLine = true); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { Button(onClick = { val r = value.toDoubleOrNull(); if (r == null || r <= 0) error = "Enter a valid rate" else { update(data.copy(rate = r)); close() } }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun PasswordDialog(data: AppData, update: (AppData) -> Unit, close: () -> Unit) {
    var value by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Change Admin Password") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, { value = it }, label = { Text("New password") }, singleLine = true)
        OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") }, singleLine = true)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = { if (value.length < 4) error = "Minimum 4 characters" else if (value != confirm) error = "Passwords do not match" else { update(data.copy(adminPassword = value)); close() } }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

private fun writeCsv(context: Context, uri: Uri, data: AppData) {
    val csv = buildString {
        append("Employee ID,Employee Name,Date,From,To,Hours,Rate,Multiplier,Amount,Cycle\n")
        data.entries.sortedBy { it.date }.forEach { e ->
            val name = data.employees.firstOrNull { it.id == e.employeeId }?.name ?: "Deleted Employee"
            append(listOf(e.employeeId, name, e.date, e.from, e.to, fmt2(e.hours), fmt2(e.rate), e.multiplier, fmt2(e.amount), cycleLabel(cycleKey(e.date))).joinToString(",") { "\"${it.toString().replace("\"", "\"\"")}\"" }).append("\n")
        }
    }
    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
}

private fun writeBackup(context: Context, uri: Uri, data: AppData) {
    val root = JSONObject().apply {
        put("backupVersion", 1); put("adminPassword", data.adminPassword); put("rate", data.rate); put("darkMode", data.darkMode)
        put("employees", JSONArray().apply { data.employees.forEach { put(JSONObject().apply { put("id", it.id); put("name", it.name); put("active", it.active); put("password", it.password) }) } })
        put("entries", JSONArray().apply { data.entries.forEach { e -> put(JSONObject().apply { put("uid", e.uid); put("employeeId", e.employeeId); put("date", e.date); put("from", e.from); put("to", e.to); put("hours", e.hours); put("rate", e.rate); put("multiplier", e.multiplier); put("amount", e.amount) }) } })
    }
    context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
}

private fun readBackup(context: Context, uri: Uri): AppData? = runCatching {
    val text = BufferedReader(InputStreamReader(context.contentResolver.openInputStream(uri)!!)).use { it.readText() }
    val root = JSONObject(text)
    val employees = mutableListOf<Employee>(); val ea = root.optJSONArray("employees") ?: JSONArray()
    for (i in 0 until ea.length()) { val o = ea.getJSONObject(i); employees += Employee(o.optString("id"), o.optString("name"), o.optBoolean("active", true), o.optString("password", o.optString("id"))) }
    val entries = mutableListOf<OtEntry>(); val oa = root.optJSONArray("entries") ?: JSONArray()
    for (i in 0 until oa.length()) { val o = oa.getJSONObject(i); entries += OtEntry(o.optString("uid"), o.optString("employeeId"), o.optString("date"), o.optString("from"), o.optString("to"), o.optDouble("hours"), o.optDouble("rate", DEFAULT_RATE), o.optInt("multiplier", 1), o.optDouble("amount")) }
    AppData(root.optString("adminPassword", "admin123"), root.optDouble("rate", DEFAULT_RATE), employees, entries, "", "", root.optBoolean("darkMode", false))
}.getOrNull()
