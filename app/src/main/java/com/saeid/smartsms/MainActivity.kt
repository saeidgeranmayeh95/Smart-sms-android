package com.saeid.smartsms

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Sms(val address: String, val body: String, val date: Long, val type: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SmartSmsApp(contentResolver) } }
    }
}

@Composable
fun SmartSmsApp(resolver: ContentResolver) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.READ_SMS) }) { Text("Allow SMS access") }
        }
    } else SmsHome(resolver)
}

@Composable
fun SmsHome(resolver: ContentResolver) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Sms>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { messages = withContext(Dispatchers.IO) { readSms(resolver) } }
    val address = selected
    if (address == null) {
        val conversations = messages.groupBy { msg -> msg.address }.values.mapNotNull { group -> group.maxByOrNull { msg -> msg.date } }.sortedByDescending { msg -> msg.date }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Smart SMS AI", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(conversations) { sms ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = sms.address }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(sms.address, style = MaterialTheme.typography.titleMedium)
                            Text(sms.body, maxLines = 2)
                        }
                    }
                }
            }
        }
    } else {
        val chat = messages.filter { msg -> msg.address == address }.sortedBy { msg -> msg.date }
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = null; suggestions = emptyList() }) { Text("Back") }
                Text(address, style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(chat) { sms ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (sms.type == 2) Arrangement.End else Arrangement.Start) {
                        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(4.dp).widthIn(max = 300.dp)) {
                            Text(sms.body, Modifier.padding(10.dp))
                        }
                    }
                }
            }
            errorText?.let { msg -> Text(msg) }
            suggestions.forEach { s -> AssistChip(onClick = {}, label = { Text(s) }) }
            Button(enabled = !loading, modifier = Modifier.fillMaxWidth(), onClick = {
                loading = true; errorText = null
                scope.launch {
                    try { suggestions = withContext(Dispatchers.IO) { getSuggestions(chat.takeLast(30)) } }
                    catch (e: Exception) { errorText = "AI error: " + (e.message ?: "unknown") }
                    loading = false
                }
            }) { Text(if (loading) "Thinking..." else "Suggest replies") }
        }
    }
}

fun readSms(resolver: ContentResolver): List<Sms> {
    val out = mutableListOf<Sms>()
    resolver.query(Telephony.Sms.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
        null, null, Telephony.Sms.DEFAULT_SORT_ORDER)?.use { c ->
        val a=c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS); val b=c.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val d=c.getColumnIndexOrThrow(Telephony.Sms.DATE); val t=c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        var count=0
        while(c.moveToNext() && count<1000){ out.add(Sms(c.getString(a)?:"Unknown",c.getString(b)?:"",c.getLong(d),c.getInt(t))); count++ }
    }
    return out
}

fun getSuggestions(chat: List<Sms>): List<String> {
    val context = chat.joinToString("\n") { msg -> (if(msg.type==2) "ME: " else "THEM: ") + msg.body }
    val body = JSONObject().put("prompt", "Conversation:\n" + context + "\nWrite replies as ME, matching my style.").toString()
    val conn=(URL("https://smart-sms-ai-backend.onrender.com/generate").openConnection() as HttpURLConnection).apply {
        requestMethod="POST"; connectTimeout=30000; readTimeout=45000; setRequestProperty("Content-Type","application/json"); doOutput=true
    }
    conn.outputStream.use { stream -> stream.write(body.toByteArray()) }
    val stream=if(conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    val text=stream.bufferedReader().use { reader -> reader.readText() }
    if(conn.responseCode !in 200..299) error("Server " + conn.responseCode + ": " + text)
    val arr:JSONArray=JSONObject(text).getJSONArray("suggestions")
    return List(arr.length()) { index -> arr.getString(index) }
}
