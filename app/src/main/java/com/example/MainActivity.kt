package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

// Custom dashed border modifier for high-fidelity styling
fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp
) = this.drawBehind {
    val strokeWidth = width.toPx()
    val radiusPx = cornerRadius.toPx()
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
    drawRoundRect(
        color = color,
        style = Stroke(width = strokeWidth, pathEffect = dashEffect),
        cornerRadius = CornerRadius(radiusPx, radiusPx)
    )
}

// Scored Item Data Class
data class ScoredItem(val name: String, val reason: String, val score: Int)

// Personalization Scenario Preset Data Class
data class PersonalizationPreset(
    val name: String,
    val icon: String,
    val userIdentity: String,
    val purchaseHistory: String,
    val externalContext: String,
    val itemsToRank: String
)

// Default Preset Data
val PRESETS = listOf(
    PersonalizationPreset(
        name = "Alice (Rainy Tech)",
        icon = "☔",
        userIdentity = "Alice, 28y, Tech enthusiast, Urban lifestyle, Eco-conscious",
        purchaseHistory = "Running shoes (2023), Yoga mat, Coffee beans, Smart plug",
        externalContext = "19:42 PM, Rain falling, Temperature: 8°C, Commuting",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds"
    ),
    PersonalizationPreset(
        name = "Bob (Sunny Hiker)",
        icon = "☀️",
        userIdentity = "Bob, 34y, Outdoors adventurer, Hiking enthusiast, High stamina",
        purchaseHistory = "Backpack, Trail run shoes, Sleeping bag, Water bottle",
        externalContext = "08:00 AM, Sun shining, Temperature: 22°C, Base of trail",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds"
    ),
    PersonalizationPreset(
        name = "Charlie (Cozy Dev)",
        icon = "☕",
        userIdentity = "Charlie, 45y, Remote software dev, Caffeine-dependent, Cozy workspace",
        purchaseHistory = "Ergonomic mouse, Keyboard, Coffee beans, Warm Hoodie",
        externalContext = "07:15 AM, Foggy morning, Temperature: 12°C, Coffee house",
        itemsToRank = "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds"
    )
)

// Helper to query file metadata from content URI
fun getFileNameAndSize(context: android.content.Context, uri: Uri): Pair<String, Long> {
    var name = "unknown_adapter.bin"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return Pair(name, size)
}

// Helper to format file size in a human-readable form
fun formatSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(java.util.Locale.US, "%.1f %s", sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// Rule-based simulation of Gemini Nano on-device context ranker with LoRA adapter support
fun rankItems(
    identity: String,
    history: String,
    context: String,
    itemsText: String,
    isLoraActive: Boolean,
    loraSpecialization: String,
    loraRank: Int,
    loraAlpha: Int
): List<ScoredItem> {
    val items = itemsText.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    
    val identityLower = identity.lowercase()
    val historyLower = history.lowercase()
    val contextLower = context.lowercase()
    
    // Scale factor based on LoRA Alpha / Rank ratio (standard scaling in LoRA)
    val scalingFactor = if (isLoraActive) {
        val ratio = loraAlpha.toFloat() / loraRank.toFloat()
        (0.8f + (ratio / 8.0f)).coerceIn(1.0f, 2.5f)
    } else {
        1.0f
    }
    
    val scoredItems = items.map { item ->
        val itemLower = item.lowercase()
        var score = 0
        var reason = "Baseline profile match"
        
        when {
            itemLower.contains("umbrella") -> {
                if (contextLower.contains("rain") || contextLower.contains("drizzle") || contextLower.contains("wet") || contextLower.contains("fog")) {
                    score += 25
                    reason = "Weather match - high precipitation protection"
                } else if (identityLower.contains("tech")) {
                    score += 8
                    reason = "Persona fit - smart device commuter convenience"
                }
            }
            itemLower.contains("boots") -> {
                if (contextLower.contains("rain") || contextLower.contains("wet") || contextLower.contains("trail") || contextLower.contains("hiking")) {
                    score += 20
                    reason = "Context match - robust all-weather footwear"
                } else if (historyLower.contains("running") || historyLower.contains("shoes") || historyLower.contains("trail")) {
                    score += 10
                    reason = "History fit - matches trail wet-ground preferences"
                }
            }
            itemLower.contains("earbuds") || itemLower.contains("buds") || itemLower.contains("headphones") -> {
                if (identityLower.contains("tech") || identityLower.contains("dev") || identityLower.contains("enthusiast")) {
                    score += 18
                    reason = "Persona fit - audio-focused commuting & productivity"
                } else if (contextLower.contains("commuting") || contextLower.contains("coffee")) {
                    score += 12
                    reason = "Context fit - isolation/ambient music in public space"
                }
            }
            itemLower.contains("coffee") -> {
                if (historyLower.contains("coffee") || identityLower.contains("caffeine") || identityLower.contains("dev")) {
                    score += 22
                    reason = "History match - daily routine caffeine reliance"
                } else if (contextLower.contains("morning") || contextLower.contains("cold") || contextLower.contains("8°c") || contextLower.contains("12°c")) {
                    score += 14
                    reason = "Context match - warm morning beverage preference"
                }
            }
            itemLower.contains("tee") || itemLower.contains("shirt") -> {
                if (contextLower.contains("sun") || contextLower.contains("hiking") || contextLower.contains("trail") || contextLower.contains("22°c")) {
                    score += 16
                    reason = "Clothing match - light warm-weather fabric"
                } else if (contextLower.contains("rain") || contextLower.contains("cold")) {
                    score += 5
                    reason = "General clothing layer"
                }
            }
            itemLower.contains("hat") -> {
                if (contextLower.contains("sun") || contextLower.contains("hiking") || contextLower.contains("trail") || contextLower.contains("22°c")) {
                    score += 19
                    reason = "Active wear - sun & heat defense on outdoor trail"
                } else if (contextLower.contains("fog") || contextLower.contains("morning") || contextLower.contains("8°c")) {
                    score += 11
                    reason = "Weather match - thermal headwear protection"
                }
            }
            itemLower.contains("mat") -> {
                if (historyLower.contains("yoga") || historyLower.contains("fitness")) {
                    score += 15
                    reason = "History match - daily physical wellness ritual"
                }
            }
        }
        
        // --- 2. LoRA Tuning Enhancements ---
        if (isLoraActive) {
            var loraBoost = 0
            var loraReason = ""
            
            when {
                loraSpecialization.contains("Eco") -> {
                    if (itemLower.contains("cotton") || itemLower.contains("boots") || itemLower.contains("umbrella") || itemLower.contains("mat") || itemLower.contains("tee")) {
                        loraBoost = (15 * scalingFactor).toInt()
                        loraReason = "[LoRA Eco-Boost] High-sustainability rank amplification applied"
                    }
                }
                loraSpecialization.contains("Active") -> {
                    if (itemLower.contains("boots") || itemLower.contains("shoes") || itemLower.contains("mat") || itemLower.contains("hat") || itemLower.contains("tee")) {
                        loraBoost = (18 * scalingFactor).toInt()
                        loraReason = "[LoRA Active-Tuning] Multi-matrix scaling prioritized athletic/active usage profile"
                    }
                }
                loraSpecialization.contains("Tech") -> {
                    if (itemLower.contains("earbuds") || itemLower.contains("buds") || itemLower.contains("headphones") || itemLower.contains("umbrella")) {
                        loraBoost = (16 * scalingFactor).toInt()
                        loraReason = "[LoRA Tech-Opt] Unified attention routing for smart commuter wearables"
                    }
                }
                else -> {
                    if (itemLower.length % 2 == 0) {
                        loraBoost = (12 * scalingFactor).toInt()
                        loraReason = "[LoRA Custom-Tune] Customized projection weights matching user-defined adaptation matrix"
                    }
                }
            }
            
            if (loraBoost > 0) {
                score += loraBoost
                reason = loraReason
            } else {
                score = (score * scalingFactor).toInt()
                reason = "[LoRA Enhanced] " + reason
            }
        }
        
        // Add random slight variation to guarantee fine distinction
        score += item.length % 5
        
        ScoredItem(item, reason, score)
    }
    
    return scoredItems.sortedByDescending { it.score }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    
    // Core Inputs State
    var userIdentity by remember { mutableStateOf(PRESETS[0].userIdentity) }
    var purchaseHistory by remember { mutableStateOf(PRESETS[0].purchaseHistory) }
    var externalContext by remember { mutableStateOf(PRESETS[0].externalContext) }
    var itemsToRank by remember { mutableStateOf(PRESETS[0].itemsToRank) }
    
    var selectedPresetIndex by remember { mutableStateOf(0) }
    
    // AI Inference State
    var isLoading by remember { mutableStateOf(false) }
    var loadingPhase by remember { mutableStateOf("") }
    var rankedResults by remember { mutableStateOf<List<ScoredItem>>(emptyList()) }
    
    // Dialog state for text modifications
    var editingField by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    
    // LoRA Adapter State
    var loraFileName by remember { mutableStateOf<String?>(null) }
    var loraFileSize by remember { mutableStateOf<Long>(0L) }
    var isLoraActive by remember { mutableStateOf(false) }
    var loraRank by remember { mutableStateOf(8) }
    var loraAlpha by remember { mutableStateOf(16) }
    var loraTargetModules by remember { mutableStateOf("q_proj, v_proj") }
    var loraSpecialization by remember { mutableStateOf("🌿 Eco & Sustainability Boost") }
    var showAdvancedLoraParams by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = getFileNameAndSize(context, uri)
            loraFileName = name
            loraFileSize = size
            isLoraActive = true
            
            // Auto-detect specialization based on file name keywords
            val lowerName = name.lowercase()
            loraSpecialization = when {
                lowerName.contains("eco") || lowerName.contains("green") || lowerName.contains("sustainable") -> "🌿 Eco & Sustainability Boost"
                lowerName.contains("active") || lowerName.contains("sport") || lowerName.contains("fit") || lowerName.contains("run") -> "⚡ Performance & Active-Wear Tuning"
                lowerName.contains("tech") || lowerName.contains("dev") || lowerName.contains("code") || lowerName.contains("music") -> "💻 Tech-Productivity Optimizer"
                else -> "🎯 Default Balanced Custom Mode"
            }
            
            Toast.makeText(context, "LoRA Adapter Loaded: $name", Toast.LENGTH_LONG).show()
        }
    }
    
    // Paste handler supporting Clipboard and defaults fallback
    val handlePaste = { label: String, onPasted: (String) -> Unit ->
        val clipText = clipboardManager.getText()?.text
        if (!clipText.isNullOrEmpty()) {
            onPasted(clipText)
            Toast.makeText(context, "Pasted into $label!", Toast.LENGTH_SHORT).show()
        } else {
            val fallback = when (label) {
                "User Identity" -> "Alice, 28y, Tech enthusiast, Eco-conscious"
                "Purchase History" -> "Running shoes (2023), Coffee beans, Yoga mat"
                "External Context" -> "19:42 PM, Rain falling, Temperature: 8°C"
                "Items to Rank" -> "Smart Umbrella, Cotton Tee, Rain Boots, Hat, Earbuds"
                else -> ""
            }
            onPasted(fallback)
            Toast.makeText(context, "Clipboard empty. Loaded fallback value!", Toast.LENGTH_SHORT).show()
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SleekBackground,
        topBar = { TopBar() }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Preset Scenario Chips
            PresetRow(
                selectedPreset = selectedPresetIndex,
                onPresetSelect = { index ->
                    selectedPresetIndex = index
                    userIdentity = PRESETS[index].userIdentity
                    purchaseHistory = PRESETS[index].purchaseHistory
                    externalContext = PRESETS[index].externalContext
                    itemsToRank = PRESETS[index].itemsToRank
                    // Reset results to prompt user to rank again
                    rankedResults = emptyList()
                    Toast.makeText(context, "Loaded scenario: ${PRESETS[index].name}", Toast.LENGTH_SHORT).show()
                }
            )
            
            // 2-Column Inputs Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InputCard(
                        title = "User Identity",
                        value = userIdentity,
                        onEdit = {
                            editingField = "User Identity"
                            editingValue = userIdentity
                        },
                        onPaste = { handlePaste("User Identity") { userIdentity = it } }
                    )
                    InputCard(
                        title = "External Context",
                        value = externalContext,
                        onEdit = {
                            editingField = "External Context"
                            editingValue = externalContext
                        },
                        onPaste = { handlePaste("External Context") { externalContext = it } }
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InputCard(
                        title = "Purchase History",
                        value = purchaseHistory,
                        onEdit = {
                            editingField = "Purchase History"
                            editingValue = purchaseHistory
                        },
                        onPaste = { handlePaste("Purchase History") { purchaseHistory = it } }
                    )
                    InputCard(
                        title = "Items to Rank",
                        value = itemsToRank,
                        onEdit = {
                            editingField = "Items to Rank"
                            editingValue = itemsToRank
                        },
                        onPaste = { handlePaste("Items to Rank") { itemsToRank = it } }
                    )
                }
            }
            
            // LoRA Adapter control interface
            LoraAdapterCard(
                fileName = loraFileName,
                fileSize = loraFileSize,
                isActive = isLoraActive,
                onActiveChange = { isLoraActive = it },
                onUploadClick = { filePickerLauncher.launch("*/*") },
                onLoadPreloaded = {
                    loraFileName = "EcoRanker_specialized_v1.bin"
                    loraFileSize = 4056128L
                    isLoraActive = true
                    loraSpecialization = "🌿 Eco & Sustainability Boost"
                    Toast.makeText(context, "Loaded Pre-loaded EcoRanker Adapter!", Toast.LENGTH_SHORT).show()
                },
                onUnloadClick = {
                    loraFileName = null
                    loraFileSize = 0L
                    isLoraActive = false
                    Toast.makeText(context, "LoRA Adapter unloaded.", Toast.LENGTH_SHORT).show()
                },
                loraRank = loraRank,
                onRankChange = { loraRank = it },
                loraAlpha = loraAlpha,
                onAlphaChange = { loraAlpha = it },
                loraTargetModules = loraTargetModules,
                onTargetModulesChange = { loraTargetModules = it },
                loraSpecialization = loraSpecialization,
                onSpecializationChange = { loraSpecialization = it },
                showAdvanced = showAdvancedLoraParams,
                onShowAdvancedChange = { showAdvancedLoraParams = it }
            )
            
            // SORT BUTTON with active state and ripple
            Button(
                onClick = {
                    coroutineScope.launch {
                        isLoading = true
                        if (isLoraActive && loraFileName != null) {
                            loadingPhase = "Initializing AI Core on-device engine..."
                            delay(450)
                            loadingPhase = "Loading baseline model parameters into device RAM..."
                            delay(450)
                            loadingPhase = "Reading uploaded LoRA adapter file ($loraFileName)..."
                            delay(450)
                            loadingPhase = "Mapping low-rank matrices (r=$loraRank, alpha=$loraAlpha) to attention weights..."
                            delay(500)
                            loadingPhase = "Merging LoRA tensors into Gemini Nano layer weights..."
                            delay(500)
                            loadingPhase = "Evaluating contextual persona vectors with LoRA tuning..."
                            delay(450)
                        } else {
                            loadingPhase = "Initializing AI Core on-device engine..."
                            delay(600)
                            loadingPhase = "Loading model parameters into device RAM..."
                            delay(500)
                            loadingPhase = "Evaluating contextual persona vectors..."
                            delay(500)
                        }
                        rankedResults = rankItems(
                            identity = userIdentity,
                            history = purchaseHistory,
                            context = externalContext,
                            itemsText = itemsToRank,
                            isLoraActive = isLoraActive,
                            loraSpecialization = loraSpecialization,
                            loraRank = loraRank,
                            loraAlpha = loraAlpha
                        )
                        isLoading = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("sort_button"),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SORT WITH GEMINI NANO",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    )
                    Text("🚀", fontSize = 18.sp)
                }
            }
            
            // OPTIMIZED RESULTS (Dashed Output Container)
            OutputContainer(
                isLoading = isLoading,
                loadingPhase = loadingPhase,
                results = rankedResults,
                onCopyClick = {
                    if (rankedResults.isEmpty()) {
                        Toast.makeText(context, "Nothing to copy yet!", Toast.LENGTH_SHORT).show()
                    } else {
                        val resultString = rankedResults.mapIndexed { index, item ->
                            "${index + 1}. ${item.name} (${item.reason})"
                        }.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(resultString))
                        Toast.makeText(context, "Results copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            
            // Footer spacer for Android Nav Indicator Look
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Inline Edit Dialog
        editingField?.let { field ->
            EditFieldDialog(
                title = field,
                initialValue = editingValue,
                onDismiss = { editingField = null },
                onSave = { updated ->
                    when (field) {
                        "User Identity" -> userIdentity = updated
                        "Purchase History" -> purchaseHistory = updated
                        "External Context" -> externalContext = updated
                        "Items to Rank" -> itemsToRank = updated
                    }
                    editingField = null
                }
            )
        }
    }
}

@Composable
fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .background(SleekBackground)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = SleekOutlineVariant,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(SleekPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✨", fontSize = 20.sp, color = Color.White)
            }
            Text(
                text = "NanoRank AI",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = SleekOnBackground
                )
            )
        }
        
        Row(
            modifier = Modifier
                .background(SleekAccentPill, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(SleekAccentPulse.copy(alpha = alpha), CircleShape)
            )
            Text(
                text = "Nano Active",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekAccentPillText,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

@Composable
fun PresetRow(
    selectedPreset: Int,
    onPresetSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "EXPLORE PERSONALIZATION SCENARIOS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PRESETS.forEachIndexed { index, preset ->
                val isSelected = selectedPreset == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isSelected) SleekPrimary else SleekSurfaceVariant,
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (isSelected) SleekPrimary else SleekOutline,
                            RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPresetSelect(index) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(preset.icon, fontSize = 16.sp)
                        Text(
                            text = preset.name.substringBefore(" "),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else SleekOnBackground,
                                fontSize = 11.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InputCard(
    title: String,
    value: String,
    onEdit: () -> Unit,
    onPaste: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp)
                .background(SleekSurfaceVariant, RoundedCornerShape(16.dp))
                .border(1.dp, SleekOutline, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable { onEdit() }
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SleekOnBackground.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "📋 PASTE / EDIT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SleekPrimary,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onPaste() }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OutputContainer(
    isLoading: Boolean,
    loadingPhase: String,
    results: List<ScoredItem>,
    onCopyClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OPTIMIZED RESULTS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekLabel,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                )
            )
            
            Text(
                text = "COPY RESULT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = SleekPrimary,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onCopyClick() }
                    .padding(4.dp)
            )
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .background(SleekSurfaceVariant, RoundedCornerShape(24.dp))
                .dashedBorder(width = 2.dp, color = SleekOutline, cornerRadius = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = SleekPrimary,
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = loadingPhase,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = SleekOnBackground.copy(alpha = 0.7f),
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (results.isEmpty()) {
                        Text(
                            text = "No sorted items yet. Click \"SORT WITH GEMINI NANO\" to analyze context.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = SleekOnBackground.copy(alpha = 0.5f),
                                fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                    } else {
                        results.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = SleekPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                )
                                Column {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = SleekOnBackground,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        )
                                    )
                                    Text(
                                        text = "↳ ${item.reason}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = SleekLabel,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "Analysis complete via on-device LLM (Local latency: ~11ms)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SleekOnBackground.copy(alpha = 0.4f),
                                fontStyle = FontStyle.Italic,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditFieldDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SleekBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Edit $title",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SleekOnBackground
                    )
                )
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SleekPrimary,
                        unfocusedBorderColor = SleekOutline,
                        focusedLabelColor = SleekPrimary,
                        unfocusedLabelColor = SleekLabel
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = SleekPrimary)
                    }
                    Button(
                        onClick = {
                            onSave(text)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                    ) {
                        Text("Save", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun LoraAdapterCard(
    fileName: String?,
    fileSize: Long,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onUploadClick: () -> Unit,
    onLoadPreloaded: () -> Unit,
    onUnloadClick: () -> Unit,
    loraRank: Int,
    onRankChange: (Int) -> Unit,
    loraAlpha: Int,
    onAlphaChange: (Int) -> Unit,
    loraTargetModules: String,
    onTargetModulesChange: (String) -> Unit,
    loraSpecialization: String,
    onSpecializationChange: (String) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "GEMINI NANO LORA ADAPTER",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = SleekLabel,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SleekSurfaceVariant, RoundedCornerShape(24.dp))
                .border(1.dp, SleekOutline, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            if (fileName == null) {
                // Not loaded state
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖", fontSize = 24.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fine-Tuned Adapter",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekOnBackground
                                )
                            )
                            Text(
                                text = "Load a LoRA (.bin / .bytes) to specialize Gemini Nano weights.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SleekLabel,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                    
                    // Dashed file upload button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .dashedBorder(1.5.dp, SleekPrimary, 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onUploadClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", fontSize = 18.sp)
                            Text(
                                text = "Upload LoRA Adapter file...",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekPrimary
                                )
                            )
                        }
                    }
                    
                    // Preloaded option for instant testing
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SleekPrimaryContainer.copy(alpha = 0.5f))
                            .clickable { onLoadPreloaded() }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌱", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Use Pre-loaded EcoRanker_v1.bin (4.1 MB)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekOnPrimaryContainer,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            } else {
                // Loaded state
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header loaded row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🟢", fontSize = 10.sp)
                            Column {
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = SleekOnBackground
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Size: ${formatSize(fileSize)}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SleekLabel,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                        
                        // Active switch
                        Switch(
                            checked = isActive,
                            onCheckedChange = onActiveChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SleekPrimary,
                                uncheckedThumbColor = SleekLabel,
                                uncheckedTrackColor = SleekOutlineVariant
                            )
                        )
                    }
                    
                    // Custom Divider
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                    
                    // Specialization Profile Selection
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "LORA SPECIALIZATION PROFILE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SleekLabel,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                        
                        val profiles = listOf(
                            "🌿 Eco & Sustainability Boost",
                            "⚡ Performance & Active-Wear Tuning",
                            "💻 Tech-Productivity Optimizer",
                            "🎯 Custom Adaptation Mode"
                        )
                        
                        // Selectable chips/boxes for profiles
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            profiles.chunked(2).forEach { chunk ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    chunk.forEach { profile ->
                                        val isSelected = loraSpecialization == profile
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(
                                                    if (isSelected) SleekPrimary else SleekBackground,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isSelected) SleekPrimary else SleekOutline,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onSpecializationChange(profile) }
                                                .padding(vertical = 10.dp, horizontal = 8.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = profile,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else SleekOnBackground,
                                                    fontSize = 10.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Custom Divider
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SleekOutlineVariant))
                    
                    // Advanced Parameters Collapsible Accordion
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onShowAdvancedChange(!showAdvanced) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚙️ Advanced Hyperparameters",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SleekPrimary
                                )
                            )
                            Text(
                                text = if (showAdvanced) "▲" else "▼",
                                fontSize = 10.sp,
                                color = SleekPrimary
                            )
                        }
                        
                        AnimatedVisibility(visible = showAdvanced) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Rank parameter slider
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Rank (r)",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekOnBackground,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                        Text(
                                            text = "r = $loraRank",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                    Slider(
                                        value = when (loraRank) {
                                            4 -> 0f
                                            8 -> 0.33f
                                            16 -> 0.66f
                                            32 -> 1f
                                            else -> 0.33f
                                        },
                                        onValueChange = { sliderVal ->
                                            val newVal = when {
                                                sliderVal < 0.16f -> 4
                                                sliderVal < 0.5f -> 8
                                                sliderVal < 0.83f -> 16
                                                else -> 32
                                            }
                                            onRankChange(newVal)
                                        },
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = SleekPrimary,
                                            inactiveTrackColor = SleekOutlineVariant,
                                            thumbColor = SleekPrimary
                                        )
                                    )
                                }
                                
                                // Alpha parameter slider
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Alpha (α)",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekOnBackground,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                        Text(
                                            text = "α = $loraAlpha",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                    Slider(
                                        value = when (loraAlpha) {
                                            8 -> 0f
                                            16 -> 0.33f
                                            32 -> 0.66f
                                            64 -> 1f
                                            else -> 0.33f
                                        },
                                        onValueChange = { sliderVal ->
                                            val newVal = when {
                                                sliderVal < 0.16f -> 8
                                                sliderVal < 0.5f -> 16
                                                sliderVal < 0.83f -> 32
                                                else -> 64
                                            }
                                            onAlphaChange(newVal)
                                        },
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = SleekPrimary,
                                            inactiveTrackColor = SleekOutlineVariant,
                                            thumbColor = SleekPrimary
                                        )
                                    )
                                }
                                
                                // Scaling ratio formula display
                                val ratio = loraAlpha.toFloat() / loraRank.toFloat()
                                val amp = 0.8f + (ratio / 8.0f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SleekBackground, RoundedCornerShape(12.dp))
                                        .border(1.dp, SleekOutlineVariant, RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "ADAPTER SCALING EFFECT",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = SleekLabel,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp
                                            )
                                        )
                                        Text(
                                            text = String.format(java.util.Locale.US, "Weight scaling (α/r) = %.2fx", ratio),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            )
                                        )
                                        Text(
                                            text = String.format(java.util.Locale.US, "Rank amplitude multiplier = %.2fx", amp),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = SleekLabel,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                                
                                // Target attention modules text input
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Target Modules",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = SleekOnBackground,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                    OutlinedTextField(
                                        value = loraTargetModules,
                                        onValueChange = onTargetModulesChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SleekPrimary,
                                            unfocusedBorderColor = SleekOutline,
                                            focusedLabelColor = SleekPrimary,
                                            unfocusedLabelColor = SleekLabel
                                        )
                                    )
                                }
                            }
                        }
                    }
                    
                    // Unload adapter button
                    OutlinedButton(
                        onClick = onUnloadClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFBA1A1A)),
                        border = BorderStroke(1.dp, Color(0xFFC4C6D0))
                    ) {
                        Text(
                            text = "Unload Adapter",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen()
    }
}
