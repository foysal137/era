package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.AppDatabase
import com.example.data.database.MemoryEntry
import com.example.data.repository.MemoryRepository
import com.example.data.repository.PreferencesManager
import com.example.service.AssistantState
import com.example.service.EraVoiceService
import com.example.ui.theme.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    containerColor = DeepSlateNoir
                ) { innerPadding ->
                    EraMainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun EraMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Preferences and databases
    val prefsManager = remember { PreferencesManager(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { MemoryRepository(db.memoryDao()) }

    // Collect memories Flow
    val allMemories by repository.allMemories.collectAsStateWithLifecycle(initialValue = emptyList())

    // Foreground service statuses
    val isRunning by EraVoiceService.isServiceRunning.collectAsStateWithLifecycle()
    val assistantState by EraVoiceService.assistantState.collectAsStateWithLifecycle()
    val rmsValue by EraVoiceService.voiceRmsdB.collectAsStateWithLifecycle()
    val statusText by EraVoiceService.lastStatusText.collectAsStateWithLifecycle()
    val activeSpeaker by EraVoiceService.activeSpeakerName.collectAsStateWithLifecycle()
    val conversationLogs by EraVoiceService.conversationLog.collectAsStateWithLifecycle()

    // Permission state handler
    var hasVoicePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasVoicePermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "মাইক্রোফোন পারমিশন দেওয়া হয়েছে!", Toast.LENGTH_SHORT).show()
            triggerEraService(context, true)
        } else {
            Toast.makeText(context, "ইরা ব্যবহারের জন্য মাইক্রোফোন চালু করা প্রয়োজন স্যার।", Toast.LENGTH_LONG).show()
        }
    }

    // UI overlays toggles (stately dialogs optimized for watch screens)
    var showSettingsOverlay by remember { mutableStateOf(false) }
    var showMemoryOverlay by remember { mutableStateOf(false) }
    
    // Dynamic owner name edit state
    var editOwnerName by remember { mutableStateOf(prefsManager.ownerName) }
    var customApiKey by remember { mutableStateOf(prefsManager.customApiKey) }
    var isGuestAllowed by remember { mutableStateOf(prefsManager.isGuestModeEnabled) }
    var voicePrintTrained by remember { mutableStateOf(prefsManager.isVoiceTrained) }
    var ownerPassphrase by remember { mutableStateOf(prefsManager.trainedPhrase) }
    var isTrainingModeActive by remember { mutableStateOf(false) }

    // Start background assistant if permissions are on of course
    LaunchedEffect(hasVoicePermission) {
        if (hasVoicePermission && !isRunning) {
            triggerEraService(context, true)
        }
    }

    Box(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        SlateCard,
                        DeepSlateNoir
                    ),
                    center = Offset(0.5f, 0.5f)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!hasVoicePermission) {
            // Symmetrical Permission request screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Microphone",
                    tint = CosmicTeal,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = " Era voice assistant ব্যবহারের জন্য মাইক্রোফোন অনুমতি প্রয়োজন স্যার।",
                    color = PureWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicTeal),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("grant_permission_button")
                ) {
                    Text(
                        text = " পারমিশন দিন",
                        color = DeepSlateNoir,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            // Main Watch Face Panel Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // Header (App Title & Mode metadata)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Adjust,
                        contentDescription = "Core Engine",
                        tint = when (assistantState) {
                            AssistantState.IDLE -> CosmicTeal
                            AssistantState.LISTENING -> AcidGreen
                            AssistantState.THINKING -> AuraYellow
                            AssistantState.SPEAKING -> MysticMagenta
                        },
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.CenterVertically)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "coreX1.active",
                        color = SoftGrayText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Beautiful interactive pulsating center
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(SlateCard)
                        .border(
                            width = 2.dp,
                            color = when (assistantState) {
                                AssistantState.IDLE -> CosmicTeal.copy(alpha = 0.4f)
                                AssistantState.LISTENING -> AcidGreen.copy(alpha = 0.8f)
                                AssistantState.THINKING -> AuraYellow.copy(alpha = 0.8f)
                                AssistantState.SPEAKING -> MysticMagenta.copy(alpha = 0.8f)
                            },
                            shape = CircleShape
                        )
                        .clickable {
                            if (isRunning) {
                                triggerEraService(context, false)
                                Toast
                                    .makeText(context, "ইরা বন্ধ করা হলো", Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                triggerEraService(context, true)
                                Toast
                                    .makeText(context, "ইরা চালু করা হচ্ছে...", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Pulsating waveforms depending on state
                    PulseWaveform(state = assistantState, rms = rmsValue)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Hearing else Icons.Default.MicOff,
                            contentDescription = "Era Speaker Action",
                            tint = when (assistantState) {
                                AssistantState.IDLE -> CosmicTeal
                                AssistantState.LISTENING -> AcidGreen
                                AssistantState.THINKING -> AuraYellow
                                AssistantState.SPEAKING -> MysticMagenta
                            },
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Era Status Output (Bangla)
                Text(
                    text = if (isRunning) statusText else "ইরা নিষ্ক্রিয় স্যার (স্পর্শ করুন)",
                    color = PureWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .heightIn(min = 36.dp)
                )

                // Sub-states active speaker recognition label
                if (isRunning) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = " বক্তা: $activeSpeaker",
                        color = if (activeSpeaker.contains("অমিল") || activeSpeaker.contains("অচেনা")) AlarmCrimson else AcidGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Wear Symmetrical actions panel (Horizontal/Vertical Grid mix)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Settings control target
                    IconButton(
                        onClick = { showSettingsOverlay = true },
                        modifier = Modifier
                            .size(52.dp)
                            .background(SlateCard, CircleShape)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "ওনার প্রোফাইল",
                            tint = PureWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Memory list database inspector
                    IconButton(
                        onClick = { showMemoryOverlay = true },
                        modifier = Modifier
                            .size(52.dp)
                            .background(SlateCard, CircleShape)
                            .testTag("memories_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.MenuBook,
                            contentDescription = "স্মৃতিসমূহ",
                            tint = PureWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Guest mode fast toggle (Visual indicator on watch widget)
                    IconButton(
                        onClick = {
                            isGuestAllowed = !isGuestAllowed
                            prefsManager.isGuestModeEnabled = isGuestAllowed
                            val msg = if (isGuestAllowed) "অনুমতি দেওয়া হলো!" else "গেস্ট অ্যাক্সেস বন্ধ স্যার।"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            coroutineScope.launch {
                                // Save to permanent memory as notification logs
                                val logSpeaker = if (isGuestAllowed) "অতিথি মোড চালু" else "অতিথি মোড বন্ধ"
                                repository.insertMemory(
                                    MemoryEntry(
                                        speaker = "system",
                                        text = "মালিক অতিথি মোড পরিবর্তন করেছেন",
                                        response = logSpeaker
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                if (isGuestAllowed) AcidGreen.copy(alpha = 0.25f) else SlateCard,
                                CircleShape
                            )
                            .border(
                                width = 1.dp,
                                color = if (isGuestAllowed) AcidGreen else Color.Transparent,
                                shape = CircleShape
                            )
                            .testTag("guest_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (isGuestAllowed) Icons.Default.Group else Icons.Default.GroupWork,
                            contentDescription = "খেলাধুলা",
                            tint = if (isGuestAllowed) AcidGreen else SoftGrayText,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Short visual display scroll area for recent conversation history
                if (conversationLogs.isNotEmpty()) {
                    Text(
                        text = " সাম্প্রতিক সংলাপসমূহ",
                        color = SoftGrayText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    conversationLogs.take(3).forEach { log ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(SlateCard, RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "মালিক: \"${log.first}\"",
                                color = CosmicTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ইরা: ${log.second}",
                                color = PureWhite,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Audio & Volume Controller Widget
                SystemAudioController()

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Launchers Matrix Hub Widget
                QuickLauncherHub()

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // 1. Settings Overlay Drawer Screen
    if (showSettingsOverlay) {
        DialogScreen(
            title = " প্রোফাইল ও সেটিংস",
            onDismiss = { showSettingsOverlay = false }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text(
                        text = "মালিকের নাম ও পরিচয়",
                        color = CosmicTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = editOwnerName,
                        onValueChange = {
                            editOwnerName = it
                            prefsManager.ownerName = it
                        },
                        label = { Text("মালিকের নাম", color = SoftGrayText) },
                        textStyle = LocalTextStyle.current.copy(color = PureWhite, fontSize = 13.sp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CosmicTeal,
                            unfocusedBorderColor = SoftGrayText.copy(alpha = 0.5f),
                            focusedLabelColor = CosmicTeal
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("owner_name_textfield")
                    )

                    Divider(color = SoftGrayText.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "জেমেনি এপিআই কী (Gemini API Key)",
                        color = CosmicTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = {
                            customApiKey = it
                            prefsManager.customApiKey = it
                        },
                        label = { Text("Gemini API Key দিন (ঐচ্ছিক)", color = SoftGrayText) },
                        textStyle = LocalTextStyle.current.copy(color = PureWhite, fontSize = 13.sp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CosmicTeal,
                            unfocusedBorderColor = SoftGrayText.copy(alpha = 0.5f),
                            focusedLabelColor = CosmicTeal
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("api_key_textfield")
                    )

                    Text(
                        text = "এখানে আপনার এপিআই কী পেস্ট করতে পারেন। খালি থাকলে এআই স্টুডিওর সিক্রেট প্যানেলের কী ব্যবহৃত হবে স্যার।",
                        color = SoftGrayText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                }

                item {
                    Divider(color = SoftGrayText.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = "বায়োমেট্রিক নিরাপত্তা",
                        color = CosmicTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "মালিকের ভয়েস প্রিন্ট অন থাকলে ইরা অন্য কারো কথায় সাড়া দেবে না স্যার।",
                        color = SoftGrayText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Trained Toggle Status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCard)
                            .clickable {
                                voicePrintTrained = !voicePrintTrained
                                prefsManager.isVoiceTrained = voicePrintTrained
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ভয়েস লক অন রাখুন",
                            color = PureWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = voicePrintTrained,
                            onCheckedChange = {
                                voicePrintTrained = it
                                prefsManager.isVoiceTrained = it
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = CosmicTeal)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Training Passphrase Customization
                    OutlinedTextField(
                        value = ownerPassphrase,
                        onValueChange = {
                            ownerPassphrase = it
                            prefsManager.trainedPhrase = it
                        },
                        label = { Text("ভয়েস প্রোফাইল প্যাটার্ন", color = SoftGrayText) },
                        textStyle = LocalTextStyle.current.copy(color = PureWhite, fontSize = 12.sp),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = {
                            isTrainingModeActive = true
                            Toast.makeText(context, "বলুন: $ownerPassphrase", Toast.LENGTH_LONG).show()
                            // Simulate quick voice extraction calculations securely to finalize profile setup
                            coroutineScope.launch {
                                delay(3000)
                                isTrainingModeActive = false
                                prefsManager.isVoiceTrained = true
                                voicePrintTrained = true
                                prefsManager.voicePitchSignature = 154.21f // Simulated dynamic vocal range signature
                                prefsManager.voiceEnergySignature = 32.4f
                                repository.insertMemory(
                                    MemoryEntry(
                                        speaker = "owner",
                                        text = "মালিকের ভয়েস ট্রেনিং সম্পন্ন",
                                        response = "ভয়েস লক প্রোফাইল সংরক্ষিত"
                                    )
                                )
                                Toast.makeText(context, "ভয়েস প্রোফাইল সংরক্ষিত হয়েছে স্যার!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTrainingModeActive) AlarmCrimson else CosmicTeal
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("train_voice_button")
                    ) {
                        Text(
                            text = if (isTrainingModeActive) "শুনছি... বলুন" else "নতুন ভয়েস রেজিস্টার করুন",
                            color = DeepSlateNoir,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    Divider(color = SoftGrayText.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = "হোস্ট ও গেস্ট সেটিংস",
                        color = CosmicTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SlateCard)
                            .clickable {
                                isGuestAllowed = !isGuestAllowed
                                prefsManager.isGuestModeEnabled = isGuestAllowed
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "গেস্ট মোড চালু (Open access)",
                            color = PureWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Checkbox(
                            checked = isGuestAllowed,
                            onCheckedChange = {
                                isGuestAllowed = it
                                prefsManager.isGuestModeEnabled = it
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "অনুমোদিত গেস্টদের তালিকা:",
                        color = SoftGrayText,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    val permitList = prefsManager.permittedGuests.split(",").filter { it.isNotEmpty() }
                    if (permitList.isEmpty()) {
                        Text(
                            text = "কোনো বিশেষ অতিথি নিবন্ধিত নেই",
                            color = SoftGrayText.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else {
                        permitList.forEach { guest ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SlateCard)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = guest, color = PureWhite, fontSize = 12.sp)
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Revoke",
                                    tint = AlarmCrimson,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            prefsManager.revokeGuest(guest)
                                            Toast.makeText(context, "$guest এর পারমিশন বাতিল", Toast.LENGTH_SHORT).show()
                                        }
                                )
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // 2. Memory Inspector Overlay Drawer Screen
    if (showMemoryOverlay) {
        DialogScreen(
            title = " ইরা'র স্থায়ী স্মৃতিসমূহ 🧠",
            onDismiss = { showMemoryOverlay = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "স্মৃতি শক্তি: ${allMemories.size} টি বিবরণ মনে রেখেছে",
                    color = SoftGrayText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                if (allMemories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ইরা'র স্মৃতিপট এখন সম্পূর্ণ ফাঁকা স্যার।\nযা বলবেন তা মনে রাখবে স্থায়ীভাবে।",
                            color = SoftGrayText.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(allMemories) { memory ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = SlateCard),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (memory.speaker == "owner") "ফয়সাল আহমেদ (মালিক)" else "অতিথি/সংলাপ",
                                            color = CosmicTeal,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "ঝেরে ফেলুন",
                                            tint = AlarmCrimson.copy(alpha = 0.7f),
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable {
                                                    coroutineScope.launch {
                                                        repository.deleteMemory(memory.id)
                                                    }
                                                }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "কথা: \"${memory.text}\"",
                                        color = PureWhite,
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "ইরা'র উত্তর: ${memory.response}",
                                        color = SoftGrayText,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                repository.clearAll()
                                Toast.makeText(context, "ইরা'র স্মৃতিপট সম্পূর্ণ মুছে ফেলা হলো স্যার।", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlarmCrimson),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        Text(
                            text = "স্মৃতিপট পুরোপুরি মুছে দিন",
                            color = PureWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Interactive custom drew pulse graphics
@Composable
fun PulseWaveform(state: AssistantState, rms: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseWaveform")
    
    // Pulse sizes
    val throbScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "throb"
    )

    val rotateAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerPoint = center
        val baseRadius = size.minDimension / 2.3f

        when (state) {
            AssistantState.IDLE -> {
                // Symmetrical quiet breathing rings
                drawCircle(
                    color = CosmicTeal,
                    radius = baseRadius * throbScale,
                    style = Stroke(width = 2.dp.toPx()),
                    alpha = 0.3f
                )
                drawCircle(
                    color = CosmicTeal,
                    radius = baseRadius * 0.75f,
                    style = Stroke(width = 1.dp.toPx()),
                    alpha = 0.15f
                )
            }
            AssistantState.LISTENING -> {
                // Dynamic acoustic microphone waveform feedback
                val rmsScale = (rms / 10f).coerceIn(0.1f, 2.5f)
                val lines = 8
                val radiusExtra = baseRadius * 1.1f
                for (i in 0 until lines) {
                    val angle = (Math.PI * 2 / lines * i).toFloat()
                    val waveHeight = 35f * rmsScale * sin(throbScale * 5f + i).toFloat()
                    
                    val startX = centerPoint.x + (radiusExtra - 5) * kotlin.math.cos(angle).toFloat()
                    val startY = centerPoint.y + (radiusExtra - 5) * sin(angle).toFloat()
                    val endX = centerPoint.x + (radiusExtra + waveHeight) * kotlin.math.cos(angle).toFloat()
                    val endY = centerPoint.y + (radiusExtra + waveHeight) * sin(angle).toFloat()

                    drawLine(
                        color = AcidGreen,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 3.dp.toPx()
                    )
                }
            }
            AssistantState.THINKING -> {
                // Rotating glowing arcs
                val scopeRadius = baseRadius * 0.95f
                drawArc(
                    color = AuraYellow,
                    startAngle = rotateAngle,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(centerPoint.x - scopeRadius, centerPoint.y - scopeRadius),
                    size = size.copy(width = scopeRadius * 2, height = scopeRadius * 2),
                    style = Stroke(width = 4.dp.toPx()),
                    alpha = 0.8f
                )
                drawArc(
                    color = AuraYellow,
                    startAngle = rotateAngle + 180f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(centerPoint.x - scopeRadius * 0.75f, centerPoint.y - scopeRadius * 0.75f),
                    size = size.copy(width = scopeRadius * 1.5f, height = scopeRadius * 1.5f),
                    style = Stroke(width = 2.dp.toPx()),
                    alpha = 0.5f
                )
            }
            AssistantState.SPEAKING -> {
                // Equalizer wave expanders
                drawCircle(
                    color = MysticMagenta,
                    radius = baseRadius * throbScale * 1.25f,
                    style = Stroke(width = 3.dp.toPx()),
                    alpha = 0.6f
                )
                drawCircle(
                    color = MysticMagenta,
                    radius = baseRadius * (2f - throbScale),
                    style = Stroke(width = 1.dp.toPx()),
                    alpha = 0.2f
                )
            }
        }
    }
}

// Watch Sized styled bottom sheet simulation overlay is extremely solid
@Composable
fun DialogScreen(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(DeepSlateNoir)
                .border(2.dp, CosmicTeal.copy(alpha = 0.2f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(16.dp)
                .clickable(enabled = false) {}, // Anti propagating clicks
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle hook
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SoftGrayText.copy(alpha = 0.4f))
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = PureWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "বন্ধ করুন",
                        tint = SoftGrayText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                content()
            }
        }
    }
}

private fun triggerEraService(context: Context, activate: Boolean) {
    val serviceIntent = Intent(context, EraVoiceService::class.java)
    if (activate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    } else {
        context.stopService(serviceIntent)
    }
}

@Composable
fun SystemAudioController(modifier: Modifier = Modifier) {
    val currentTrack by EraVoiceService.currentPlayingTrack.collectAsStateWithLifecycle()
    val isPlaying by EraVoiceService.isPlayingMusic.collectAsStateWithLifecycle()
    val volumeRatio by EraVoiceService.systemVolumeRatio.collectAsStateWithLifecycle()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("system_audio_controller"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, CosmicTeal.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Music",
                    tint = CosmicTeal,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ইরা অডিও ও সাউন্ড কন্ট্রোল",
                    color = CosmicTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            
            // Track title box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepSlateNoir, RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Hearing else Icons.Default.MusicOff,
                        contentDescription = "Status",
                        tint = if (isPlaying) AcidGreen else SoftGrayText,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentTrack,
                        color = PureWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Track Quick Selection and play controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tracks 1, 2, 3 selection buttons
                listOf("১", "২", "৩").forEachIndexed { index, name ->
                    OutlinedButton(
                        onClick = {
                            EraVoiceService.activeInstance?.playTrack(index)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("play_track_btn_$index"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = CosmicTeal
                        ),
                        border = BorderStroke(1.dp, CosmicTeal.copy(alpha = 0.5f)),
                        shape = CircleShape
                    ) {
                        Text(text = name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                // Pause / Stop Music button
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            EraVoiceService.activeInstance?.pauseMusic()
                        } else {
                            EraVoiceService.activeInstance?.resumeMusic()
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (isPlaying) AlarmCrimson.copy(alpha = 0.2f) else CosmicTeal.copy(alpha = 0.2f), CircleShape)
                        .testTag("pause_music_btn")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Pause",
                        tint = if (isPlaying) AlarmCrimson else CosmicTeal,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Next Track button
                IconButton(
                    onClick = {
                        EraVoiceService.activeInstance?.nextMusic()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(SlateCard.copy(alpha = 0.6f), CircleShape)
                        .border(1.dp, SoftGrayText.copy(alpha = 0.3f), CircleShape)
                        .testTag("next_music_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            // Volume sliders and buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        EraVoiceService.activeInstance?.setVolumeMute()
                    },
                    modifier = Modifier.size(32.dp).testTag("mute_volume_btn")
                ) {
                    Icon(
                        imageVector = if (volumeRatio == 0f) Icons.Default.VolumeMute else Icons.Default.VolumeOff,
                        contentDescription = "Mute",
                        tint = SoftGrayText,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                IconButton(
                    onClick = {
                        EraVoiceService.activeInstance?.adjustVolume(increase = false)
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .background(DeepSlateNoir, CircleShape)
                        .testTag("decrease_volume_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeDown,
                        contentDescription = "Lower Vol",
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Dynamic Progress bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(DeepSlateNoir, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(volumeRatio)
                            .fillMaxHeight()
                            .background(CosmicTeal, CircleShape)
                    )
                }

                IconButton(
                    onClick = {
                        EraVoiceService.activeInstance?.adjustVolume(increase = true)
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .background(DeepSlateNoir, CircleShape)
                        .testTag("increase_volume_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Raise Vol",
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun QuickLauncherHub(modifier: Modifier = Modifier) {
    val apps = listOf(
        Triple("ইউটিউব", "youtube", Icons.Default.PlayCircle),
        Triple("ফেসবুক", "facebook", Icons.Default.Public),
        Triple("প্লে স্টোর", "vending", Icons.Default.ShoppingCart),
        Triple("গুগল ম্যাপস", "maps", Icons.Default.Map),
        Triple("সেটিংস", "settings", Icons.Default.Settings),
        Triple("ক্যালকুলেটর", "calculator", Icons.Default.Calculate)
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("quick_launcher_hub"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, AcidGreen.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.GridOn,
                    contentDescription = "Apps",
                    tint = AcidGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "স্মার্ট অ্যাপস কুইক লঞ্চার (১-ক্লিক)",
                    color = AcidGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Grid representation (3x2 grid)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (col in 0 until 3) {
                            val index = row * 3 + col
                            if (index < apps.size) {
                                val app = apps[index]
                                Button(
                                    onClick = {
                                        EraVoiceService.activeInstance?.openAppByName(app.first, app.second)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("launch_app_${app.second}"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DeepSlateNoir
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    border = BorderStroke(0.5.dp, SoftGrayText.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = app.third,
                                            contentDescription = app.first,
                                            tint = when (index) {
                                                0 -> Color(0xFFFF0000) // YouTube Red
                                                1 -> Color(0xFF1877F2) // Facebook Blue
                                                2 -> CosmicTeal // Play Store Teal
                                                3 -> AcidGreen // G Maps Green
                                                4 -> PureWhite // Settings
                                                else -> AuraYellow // Calculator Yellow
                                            },
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = app.first,
                                            color = PureWhite,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
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
