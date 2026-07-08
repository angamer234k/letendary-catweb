package com.yourname.decalmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import kotlin.math.ceil

// ========================== DATA CLASSES ================================
data class Entry(val name: String, val url: String, val desc: String)

// ========================== RETROFIT (PUBLIC API) =======================
interface RobloxPublicApi {
    @GET("v1/assets/{assetId}/details")
    suspend fun getAssetDetails(@Path("assetId") assetId: String): AssetDetailsResponse
}

data class AssetDetailsResponse(
    val Description: String? = null,
    val Name: String? = null
)

object RetrofitClient {
    private val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
    private val client = OkHttpClient.Builder().addInterceptor(logging).build()

    val publicApi: RobloxPublicApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.roblox.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RobloxPublicApi::class.java)
    }
}

// ========================== DATASTORE ===================================
val Context.dataStore by preferencesDataStore(name = "decal_manager_prefs")
val DECAL_ID_KEY = stringPreferencesKey("decal_id")

// ========================== VIEWMODEL ==================================
class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var entryList = mutableListOf<Entry>()
    private var currentAssetId = ""
    private var foundEntriesForDelete = listOf<Entry>()

    data class UiState(
        val isLoading: Boolean = false,
        val assetIdInput: String = "",
        val entries: List<Entry> = emptyList(),
        val charCount: Int = 0,
        val showDeleteDialog: Boolean = false,
        val deleteFoundList: List<Entry> = emptyList(),
        val deletePage: Int = 0,
        val totalDeletePages: Int = 0,
        val statusMessage: String = "",
        val showAddDialog: Boolean = false,
        val showDeleteQueryDialog: Boolean = false
    )

    fun updateAssetIdInput(input: String) {
        _uiState.value = _uiState.value.copy(assetIdInput = input)
    }

    fun setStatus(msg: String) {
        _uiState.value = _uiState.value.copy(statusMessage = msg)
    }

    fun loadFromAssetId(assetId: String) {
        if (assetId.isBlank()) { setStatus("Enter an Asset ID"); return }
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val response = RetrofitClient.publicApi.getAssetDetails(assetId)
                val desc = response.Description ?: ""
                currentAssetId = assetId
                entryList = parseDescription(desc).toMutableList()
                _uiState.value = _uiState.value.copy(
                    entries = entryList,
                    charCount = desc.length,
                    statusMessage = "Loaded ${entryList.size} entries from asset $assetId",
                    isLoading = false
                )
                LocalContext.current.dataStore.edit { prefs ->
                    prefs[DECAL_ID_KEY] = assetId
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Load failed: ${e.message}"
                )
            }
        }
    }

    fun importDescription(rawDesc: String) {
        if (rawDesc.isBlank()) { setStatus("Description is empty"); return }
        entryList = parseDescription(rawDesc).toMutableList()
        updateLocalState()
        setStatus("Imported ${entryList.size} entries from clipboard")
    }

    fun addEntry(name: String, url: String, desc: String) {
        val finalName = name.take(15)
        val finalUrl = url.take(35)
        val finalDesc = desc.take(50)

        if (finalName.isBlank() || finalUrl.isBlank()) {
            setStatus("Name and URL required")
            return
        }
        if (entryList.any { it.url.equals(finalUrl, ignoreCase = true) }) {
            setStatus("URL already exists")
            return
        }
        entryList.add(Entry(finalName, finalUrl, finalDesc))
        updateLocalState()
        val newDesc = rebuildDescription()
        if (newDesc.length > 950) {
            setStatus("⚠️ Close to 1000 char limit! (${newDesc.length}/1000)")
        } else {
            setStatus("Added locally. Copy to clipboard to save.")
        }
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun findAndShowDelete(query: String) {
        if (query.isBlank()) { setStatus("Enter a name or URL to search"); return }
        val lower = query.lowercase()
        var found = entryList.filter { it.name.lowercase().contains(lower) }
        if (found.isEmpty()) {
            found = entryList.filter { it.url.lowercase().contains(lower) }
        }
        if (found.isEmpty()) {
            setStatus("No entries match")
            _uiState.value = _uiState.value.copy(showDeleteDialog = false, showDeleteQueryDialog = false)
            return
        }
        foundEntriesForDelete = found
        val totalPages = ceil(found.size / 5.0).toInt()
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = true,
            showDeleteQueryDialog = false,
            deleteFoundList = found,
            deletePage = 0,
            totalDeletePages = totalPages
        )
    }

    fun confirmDeleteFound() {
        val toDelete = foundEntriesForDelete
        if (toDelete.isEmpty()) return
        entryList.removeAll { entry ->
            toDelete.any { it.name == entry.name && it.url == entry.url }
        }
        foundEntriesForDelete = listOf()
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = false,
            deleteFoundList = emptyList()
        )
        updateLocalState()
        setStatus("Deleted ${toDelete.size} entries. Copy to clipboard to save.")
    }

    fun changeDeletePage(delta: Int) {
        val current = _uiState.value.deletePage
        val total = _uiState.value.totalDeletePages
        val newPage = (current + delta).coerceIn(0, total - 1)
        _uiState.value = _uiState.value.copy(deletePage = newPage)
    }

    fun copyToClipboard(context: Context) {
        val desc = rebuildDescription()
        if (desc.isEmpty()) { setStatus("No entries to copy"); return }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Decal Description", desc)
        clipboard.setPrimaryClip(clip)
        setStatus("Copied to clipboard! (${desc.length} chars)")
        Toast.makeText(context, "Copied description to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun pasteFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            setStatus("Clipboard is empty")
            return
        }
        val text = clip.getItemAt(0).text.toString()
        if (text.isBlank()) {
            setStatus("Clipboard is empty")
            return
        }
        importDescription(text)
    }

    private fun rebuildDescription(): String {
        if (entryList.isEmpty()) return ""
        var raw = entryList.joinToString(separator = "/", prefix = "/") {
            "${it.name}:${it.url}:${it.desc}"
        }
        if (raw.length > 1000) {
            raw = raw.take(1000)
            val lastSlash = raw.lastIndexOf('/')
            if (lastSlash > 0) raw = raw.substring(0, lastSlash)
            setStatus("⚠️ Auto-trimmed to fit 1000 char limit!")
        }
        return raw
    }

    private fun parseDescription(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        val segments = raw.split("/").filter { it.isNotEmpty() }
        return segments.mapNotNull { seg ->
            val parts = seg.split(":", limit = 3)
            if (parts.size == 3) Entry(parts[0], parts[1], parts[2]) else null
        }
    }

    private fun updateLocalState() {
        val newDesc = rebuildDescription()
        _uiState.value = _uiState.value.copy(
            entries = entryList,
            charCount = newDesc.length,
            statusMessage = "${entryList.size} entries"
        )
    }
}

// ========================== MAIN ACTIVITY ===============================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    val prefs = context.dataStore
                    val savedId = prefs.edit { it[DECAL_ID_KEY] }.firstOrNull() ?: ""
                    if (savedId.isNotEmpty()) {
                        viewModel.updateAssetIdInput(savedId)
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("🥫 DECAL MANAGER (Manual)", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = uiState.assetIdInput,
                            onValueChange = { viewModel.updateAssetIdInput(it) },
                            label = { Text("Asset ID") },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isLoading
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.loadFromAssetId(uiState.assetIdInput) }, enabled = !uiState.isLoading) {
                            Text("Load")
                        }
                    }

                    Text(uiState.statusMessage, color = MaterialTheme.colorScheme.primary)
                    Text("Chars: ${uiState.charCount}/1000", color = if (uiState.charCount > 950) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(uiState.entries) { entry ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("📛 ${entry.name}", style = MaterialTheme.typography.titleSmall)
                                    Text("🔗 ${entry.url}", style = MaterialTheme.typography.bodySmall)
                                    Text("📝 ${entry.desc.take(50)}${if (entry.desc.length > 50) "..." else ""}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (uiState.entries.isEmpty()) {
                            item { Text("No entries loaded.", modifier = Modifier.padding(16.dp)) }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { _uiState.value = _uiState.value.copy(showAddDialog = true) }) { Text("➕ Add") }
                        Button(onClick = { _uiState.value = _uiState.value.copy(showDeleteQueryDialog = true) }) { Text("🗑️ Delete") }
                        Button(onClick = { viewModel.copyToClipboard(context) }, enabled = uiState.entries.isNotEmpty()) {
                            Text("📋 Copy")
                        }
                        Button(onClick = { viewModel.pasteFromClipboard(context) }) {
                            Text("📥 Paste")
                        }
                    }
                }

                // Add Dialog
                if (uiState.showAddDialog) {
                    var addName by remember { mutableStateOf("") }
                    var addUrl by remember { mutableStateOf("") }
                    var addDesc by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { _uiState.value = _uiState.value.copy(showAddDialog = false) },
                        title = { Text("Add Entry") },
                        text = {
                            Column {
                                Column {
                                    OutlinedTextField(
                                        value = addName,
                                        onValueChange = { if (it.length <= 15) addName = it },
                                        label = { Text("Name (max 15)") },
                                        singleLine = true
                                    )
                                    Text("${addName.length}/15", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Column {
                                    OutlinedTextField(
                                        value = addUrl,
                                        onValueChange = { if (it.length <= 35) addUrl = it },
                                        label = { Text("URL (max 35)") },
                                        singleLine = true
                                    )
                                    Text("${addUrl.length}/35", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Column {
                                    OutlinedTextField(
                                        value = addDesc,
                                        onValueChange = { if (it.length <= 50) addDesc = it },
                                        label = { Text("Description (max 50)") },
                                        singleLine = true
                                    )
                                    Text("${addDesc.length}/50", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = { viewModel.addEntry(addName, addUrl, addDesc) }) { Text("Add") }
                        },
                        dismissButton = {
                            Button(onClick = { _uiState.value = _uiState.value.copy(showAddDialog = false) }) { Text("Cancel") }
                        }
                    )
                }

                // Delete Query Dialog
                if (uiState.showDeleteQueryDialog) {
                    var deleteQuery by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { _uiState.value = _uiState.value.copy(showDeleteQueryDialog = false) },
                        title = { Text("Enter name or URL to delete") },
                        text = {
                            OutlinedTextField(
                                value = deleteQuery,
                                onValueChange = { deleteQuery = it },
                                label = { Text("Search (partial match)") }
                            )
                        },
                        confirmButton = {
                            Button(onClick = { viewModel.findAndShowDelete(deleteQuery) }) { Text("Find") }
                        },
                        dismissButton = {
                            Button(onClick = { _uiState.value = _uiState.value.copy(showDeleteQueryDialog = false) }) { Text("Cancel") }
                        }
                    )
                }

                // Delete Found Dialog (Pagination)
                if (uiState.showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { _uiState.value = _uiState.value.copy(showDeleteDialog = false) },
                        title = { Text("Found ${uiState.deleteFoundList.size} entries") },
                        text = {
                            Column {
                                val pageList = uiState.deleteFoundList.drop(uiState.deletePage * 5).take(5)
                                pageList.forEachIndexed { idx, entry ->
                                    Text("${uiState.deletePage * 5 + idx + 1}. ${entry.name} | ${entry.url} | ${entry.desc.take(30)}")
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Button(onClick = { viewModel.changeDeletePage(-1) }, enabled = uiState.deletePage > 0) { Text("Prev") }
                                    Text("Page ${uiState.deletePage + 1}/${uiState.totalDeletePages}")
                                    Button(onClick = { viewModel.changeDeletePage(1) }, enabled = uiState.deletePage < uiState.totalDeletePages - 1) { Text("Next") }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = { viewModel.confirmDeleteFound() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text("Delete All Found")
                            }
                        },
                        dismissButton = {
                            Button(onClick = { _uiState.value = _uiState.value.copy(showDeleteDialog = false) }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}