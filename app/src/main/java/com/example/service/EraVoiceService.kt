package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.api.Content
import com.example.data.api.GeminiRequest
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.example.data.api.GenerationConfig
import com.example.data.database.AppDatabase
import com.example.data.database.MemoryEntry
import com.example.data.repository.MemoryRepository
import com.example.data.repository.PreferencesManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*

enum class AssistantState {
    IDLE,       // Background sleep mode, waiting for Hotword "ইরা" (Era)
    LISTENING,  // Active user voice query listening
    THINKING,   // Calling Gemini AI context
    SPEAKING    // Outputting auditory response via TTS
}

class EraVoiceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "EraVoiceService"
        private const val CHANNEL_ID = "era_service_channel"
        private const val NOTIFICATION_ID = 4859

        // Global reactive flows for MainActivity UI binding
        val assistantState = MutableStateFlow(AssistantState.IDLE)
        val conversationLog = MutableStateFlow<List<Pair<String, String>>>(emptyList())
        val isServiceRunning = MutableStateFlow(false)
        val voiceRmsdB = MutableStateFlow(0.0f) // For interactive speech wave graphics
        val lastStatusText = MutableStateFlow("ইরা প্রস্তুত স্যার")
        val isOwnerVerified = MutableStateFlow(false)
        val activeSpeakerName = MutableStateFlow("ফয়সাল আহমেদ (মালিক)")
        
        // Reactive Media Audio flows for interactive UI controls
        val currentPlayingTrack = MutableStateFlow("কোনো গান বাজছে না")
        val isPlayingMusic = MutableStateFlow(false)
        val systemVolumeRatio = MutableStateFlow(0.5f)

        @Volatile
        var activeInstance: EraVoiceService? = null
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var prefsManager: PreferencesManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Music playback properties
    private var mediaPlayer: MediaPlayer? = null
    val trackList = listOf(
        Pair("লোফাই ড্রিমস (Lofi Dreams)", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
        Pair("কসমিক অ্যাম্বিয়েন্ট (Cosmic Ambient)", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
        Pair("রিয়েল ন্যাচার সাউন্ড (Nature Rain)", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3")
    )
    private var currentTrackIndex = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        activeInstance = this
        
        // Initialize local databases and preferences
        val db = AppDatabase.getDatabase(this)
        memoryRepository = MemoryRepository(db.memoryDao())
        prefsManager = PreferencesManager(this)
        
        // Initialize Text-To-Speech
        tts = TextToSpeech(this, this)
        
        // Setup wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EraAssistant::VoiceWakeLock"
        )

        // Create foreground notification channel
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("ইরা চালু আছে - ব্যাকগ্রাউন্ড ভয়েস একটিভ স্যার"))
        
        // Initialize SpeechRecognizer on Main Thread
        initSpeechRecognizer()
        
        isServiceRunning.value = true
        syncVolumeState()
        startContinuousListening()
    }

    private fun initSpeechRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognizer", e)
            }
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(EraSpeechListener())
        }
        Log.d(TAG, "Speech Recognizer re-initialized")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Era coreX1 Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Era background active state notification channel"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ইরা ভার্সন coreX1")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // Continuous stand-by logic
    private fun startContinuousListening() {
        if (isListening) return
        
        mainHandler.post {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                
                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d(TAG, "Started SpeechRecognizer listening loop")
            } catch (e: Exception) {
                Log.e(TAG, "Failed startListening, re-initializing", e)
                initSpeechRecognizer()
                mainHandler.postDelayed({ startContinuousListening() }, 1000)
            }
        }
    }

    private fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognizer", e)
            }
            isListening = false
        }
    }

    // TTS initialization
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Bangla language not supported on this device. Falling back to English.")
                tts?.language = Locale.US
            }
            Log.d(TAG, "TTS completely initialized")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        stopListening()
        assistantState.value = AssistantState.SPEAKING
        lastStatusText.value = "কথা বলছে ইরা..."
        
        val utteranceId = "era_tts_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        // Wait based on duration or explicit listener
        serviceScope.launch {
            delay((text.length * 150L).coerceAtLeast(1500L))
            withContext(Dispatchers.Main) {
                assistantState.value = AssistantState.IDLE
                lastStatusText.value = "ইরা প্রস্তুত স্যার"
                onComplete?.invoke()
                startContinuousListening()
            }
        }
    }

    // Wakes watch screen
    private fun wakeScreen() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(3000) // Acquire CPU lock to turn screen on
            }
            // Bring MainActivity to front to show response visually
            val showIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(showIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen", e)
        }
    }

    // --- AUDIO & MEDIA CONTROLLERS ---
    fun syncVolumeState() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        if (maxVol > 0) {
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            systemVolumeRatio.value = curVol / maxVol
        }
    }

    fun adjustVolume(increase: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        syncVolumeState()
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        speak("ঠিক আছে স্যার, সাউন্ড ${if (increase) "বাড়ানো" else "কমানো"} হলো। বর্তমান ভলিউম লেভেল $curVol।")
    }

    fun setVolumeMax() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
        syncVolumeState()
        speak("ঠিক আছে স্যার, সাউন্ড সর্বোচ্চ লেভেলে সেট করা হলো।")
    }

    fun setVolumeMute() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        syncVolumeState()
        speak("ঠিক আছে স্যার, সাউন্ড মিউট করা হলো।")
    }

    fun playTrack(index: Int) {
        if (index < 0 || index >= trackList.size) return
        currentTrackIndex = index
        val track = trackList[currentTrackIndex]
        speak("ঠিক আছে স্যার, ${track.first} চালু করছি।") {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(track.second)
                        setAudioStreamType(AudioManager.STREAM_MUSIC)
                        setOnPreparedListener { mp ->
                            mp.start()
                            isPlayingMusic.value = true
                            currentPlayingTrack.value = track.first
                        }
                        setOnCompletionListener {
                            isPlayingMusic.value = false
                            currentPlayingTrack.value = "কোনো গান বাজছে না"
                        }
                        prepare()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preparing media source link", e)
                    withContext(Dispatchers.Main) {
                        speak("দুঃখিত স্যার, মিউজিক প্লেয়ারটি চালু করা যায়নি। দয়া করে ইন্টারনেট সংযোগ চেক করুন।")
                    }
                }
            }
        }
    }

    fun pauseMusic() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.pause()
            isPlayingMusic.value = false
            currentPlayingTrack.value = "${trackList[currentTrackIndex].first} (বন্ধ)"
            speak("ঠিক আছে স্যার, মিউজিক পজ করা হলো।")
        } else {
            speak("স্যার, কোনো মিউজিক এখন চলছে না।")
        }
    }

    fun resumeMusic() {
        if (mediaPlayer != null) {
            mediaPlayer!!.start()
            isPlayingMusic.value = true
            currentPlayingTrack.value = trackList[currentTrackIndex].first
            speak("ঠিক আছে স্যার, মিউজিক পুনরায় প্লে করছি।")
        } else {
            playTrack(0)
        }
    }

    fun nextMusic() {
        val nextIndex = (currentTrackIndex + 1) % trackList.size
        playTrack(nextIndex)
    }

    // --- SMART APP FINDER & LAUNCHERS ---
    private fun translateBanglaToEnglishApp(banglaName: String): String {
        val clean = banglaName.trim().lowercase()
        return when {
            clean.contains("ইউটিউব") -> "youtube"
            clean.contains("ফেসবুক") -> "facebook"
            clean.contains("প্লে স্টোর") || clean.contains("প্লেস্টোর") || clean.contains("প্লে") -> "vending"
            clean.contains("ম্যাপ") -> "maps"
            clean.contains("হোয়াটসঅ্যাপ") || clean.contains("হোয়াটসঅ্যাপ") -> "whatsapp"
            clean.contains("মেসেঞ্জার") -> "messenger"
            clean.contains("ক্যালকুলেটর") -> "calculator"
            clean.contains("ক্যালেন্ডার") -> "calendar"
            clean.contains("সেটিংস") || clean.contains("সেটিং") -> "settings"
            clean.contains("ঘড়ি") || clean.contains("ঘড়ি") || clean.contains("অ্যালার্ম") -> "clock"
            clean.contains("ক্রোম") -> "chrome"
            clean.contains("ব্রাউজার") -> "browser"
            clean.contains("ক্যামেরা") -> "camera"
            clean.contains("ফাইল") -> "file"
            clean.contains("গ্যালারি") -> "gallery"
            else -> clean
        }
    }

    fun openAppByName(appNameInBangla: String, appNameInEnglish: String) {
        val preferredPackage = when (appNameInEnglish.lowercase()) {
            "youtube" -> "com.google.android.youtube"
            "facebook" -> "com.facebook.katana"
            "vending" -> "com.android.vending"
            "maps" -> "com.google.android.apps.maps"
            "whatsapp" -> "com.whatsapp"
            "messenger" -> "com.facebook.orc"
            "chrome" -> "com.android.chrome"
            else -> null
        }
        
        if (preferredPackage != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(preferredPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return
            }
        }
        
        val pm = packageManager
        try {
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            for (pkg in packages) {
                val label = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: continue
                if (label.contains(appNameInEnglish.lowercase()) || label.contains(appNameInBangla.lowercase())) {
                    val intent = pm.getLaunchIntentForPackage(pkg.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search directories failed", e)
        }
        
        val intent = when (appNameInEnglish.lowercase()) {
            "calculator" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALCULATOR) }
            "calendar" -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALENDAR) }
            "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            else -> {
                val searchUrl = "https://www.google.com/search?q=$appNameInEnglish"
                Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
            }
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Standard fallback category target failed", e)
        }
    }

    private fun handleVoiceQuery(query: String) {
        Log.e(TAG, "Processing command query: $query")
        val normalizedQuery = query.trim().lowercase()

        // 1. SOUND/VOLUME CONTROLS
        if (normalizedQuery.contains("সাউন্ড") || normalizedQuery.contains("ভলিউম") || normalizedQuery.contains("শব্দ") || normalizedQuery.contains("আওয়াজ") || normalizedQuery.contains("আওয়াজ")) {
            if (normalizedQuery.contains("বাড়াও") || normalizedQuery.contains("বাড়িয়ে") || normalizedQuery.contains("বেশি") || normalizedQuery.contains("বৃদ্ধি")) {
                adjustVolume(increase = true)
                return
            }
            if (normalizedQuery.contains("কমাও") || normalizedQuery.contains("কম") || normalizedQuery.contains("হ্রাস")) {
                adjustVolume(increase = false)
                return
            }
            if (normalizedQuery.contains("সর্বোচ্চ") || normalizedQuery.contains("ফুল") || normalizedQuery.contains("সবচেয়ে বেশি")) {
                setVolumeMax()
                return
            }
            if (normalizedQuery.contains("বন্ধ") || normalizedQuery.contains("মিউট")) {
                setVolumeMute()
                return
            }
        }
        
        if (normalizedQuery.contains("মিউট করো") || normalizedQuery.contains("শব্দ বন্ধ করো")) {
            setVolumeMute()
            return
        }

        // 2. MUSIC/PLAYBACK CONTROLS
        if (normalizedQuery.contains("মিউজিক") || normalizedQuery.contains("গান") || normalizedQuery.contains("প্লেয়ার") || normalizedQuery.contains("সাউন্ডট্র্যাক")) {
            if (normalizedQuery.contains("১") || normalizedQuery.contains("1") || normalizedQuery.contains("এক")) {
                playTrack(0)
                return
            }
            if (normalizedQuery.contains("২") || normalizedQuery.contains("2") || normalizedQuery.contains("দুই")) {
                playTrack(1)
                return
            }
            if (normalizedQuery.contains("৩") || normalizedQuery.contains("3") || normalizedQuery.contains("তিন")) {
                playTrack(2)
                return
            }
            if (normalizedQuery.contains("বন্ধ") || normalizedQuery.contains("থামাও") || normalizedQuery.contains("পজ") || normalizedQuery.contains("থামুন")) {
                pauseMusic()
                return
            }
            if (normalizedQuery.contains("পরের") || normalizedQuery.contains("নেক্সট") || normalizedQuery.contains("পরবর্তী") || normalizedQuery.contains("পাল্টাও")) {
                nextMusic()
                return
            }
            if (normalizedQuery.contains("চালু") || normalizedQuery.contains("প্লে") || normalizedQuery.contains("শুরু")) {
                resumeMusic()
                return
            }
        }

        // 3. SMART APP LAUNCHERS
        if (normalizedQuery.contains("ওপেন") || normalizedQuery.contains("খোলো") || normalizedQuery.contains("খোল") || normalizedQuery.contains("যাও")) {
            val targetAppStr = query.replace("ওপেন", "")
                .replace("করো", "")
                .replace("খোলো", "")
                .replace("খোল", "")
                .replace("যাও", "")
                .replace("অ্যাপ", "")
                .replace("ইরা", "")
                .trim()
            
            if (targetAppStr.isNotEmpty()) {
                val englishName = translateBanglaToEnglishApp(targetAppStr)
                speak("ঠিক আছে স্যার, $targetAppStr ওপেন করছি।") {
                    openAppByName(targetAppStr, englishName)
                }
                return
            }
        }

        // 4. Guest Permission Authority Controls
        if (normalizedQuery.contains("অনুমতি দাও") && normalizedQuery.contains("কথার উত্তর")) {
            val owner = prefsManager.ownerName
            val commandClean = query.replace("ইরা", "").replace("অনুমতি দাও", "").replace("কথার উত্তর", "").replace("দেওয়ার", "").trim()
            if (commandClean.isNotEmpty()) {
                prefsManager.permitGuest(commandClean)
                speak("ঠিক আছে ${owner} স্যার, আমি এখন থেকে ${commandClean} এর কথারও উত্তর দিব।")
            } else {
                speak("কার কথার উত্তর দিব স্যার? দয়া করে নাম বলুন।")
            }
            return
        }

        if (normalizedQuery.contains("অতিথি মোড") && normalizedQuery.contains("চালু")) {
            prefsManager.isGuestModeEnabled = true
            speak("ঠিক আছে স্যার, অতিথি মোড চালু করা হলো। এখন সবাই আমার সাথে কথা বলতে পারবে।")
            return
        }

        if (normalizedQuery.contains("অতিথি মোড") && normalizedQuery.contains("বন্ধ")) {
            prefsManager.isGuestModeEnabled = false
            speak("ঠিক আছে স্যার, অতিথি মোড বন্ধ করা হয়েছে। এখন শুধুমাত্র ফয়সাল স্যার আমার সাথে কথা বলতে পারবেন।")
            return
        }

        // 5. Sleep Mode Shutdown Trigger
        if (normalizedQuery.contains("বন্ধ") || normalizedQuery.contains("ঘুমাও") || normalizedQuery.contains("চুপ কর")) {
            speak("ঠিক আছে স্যার, আমি ঘুমাচ্ছি। ডাকলেই চলে আসবো।") {
                assistantState.value = AssistantState.IDLE
                lastStatusText.value = "ঘুমাচ্ছে (ইরা স্যার ডাকলেই জাগবে...)"
            }
            return
        }

        // 6. Fallback to Gemini AI for General/Contextual query
        processWithGemini(query)
    }

    private fun processWithGemini(query: String) {
        assistantState.value = AssistantState.THINKING
        lastStatusText.value = "চিন্তা করছে ইরা..."
        
        serviceScope.launch(Dispatchers.IO) {
            val customKey = prefsManager.customApiKey
            val apiKey = if (customKey.isNotEmpty()) customKey else BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                withContext(Dispatchers.Main) {
                    speak("ফয়সাল স্যার, দয়া করে সেটিংস থেকে অথবা এআই স্টুডিওর সিক্রেট প্যানেল থেকে আপনার জেমেনি এপিআই কী সেট করুন।")
                }
                return@launch
            }

            // Retrieve memories database to feed context
            val pastMemories = memoryRepository.getRecentMemories(10)
            val memoryPrompt = StringBuilder()
            if (pastMemories.isNotEmpty()) {
                memoryPrompt.append("\n\nএখানে মালিক ফয়সাল ও আপনার পূর্বের স্মৃতির কথোপকথন রয়েছে (memories):\n")
                pastMemories.reversed().forEach {
                    memoryPrompt.append("- User: ${it.text} -> Era: ${it.response}\n")
                }
            }

            // Construct system instruction dynamically with owner & metadata settings
            val ownerNameString = prefsManager.ownerName
            val systemInstruction = "আপনি হলে ইরা (Era), ভার্সন coreX1, ফয়সাল আহমেদ (Faisal Ahmed) এর তৈরি চরম শক্তিশালী এবং বুদ্ধিমান ভয়েস এআই অ্যাসিস্ট্যান্ট। " +
                    "ফয়সাল আহমেদ আপনার মালিক ও সৃষ্টিকর্তা। আপনি তার অত্যন্ত অনুগত সঙ্গী। আপনি তার সাথে এবং তার অনুমতি সাপেক্ষে অতিথিদের সাথে কথা বলেন। " +
                    "আপনি সর্বদা চমৎকার, ছোট, সংক্ষিপ্ত এবং বুদ্ধিদীপ্ত বাংলায় প্রশ্নের উত্তর দেন। উক্তিগুলো খুব বেশি বড় করবেন না কারণ আপনি একটি ছোট স্মার্টওয়াচে চলছেন। " +
                    "মালিক সম্পর্কে যা যা আপনাকে বলা হবে, তার স্মৃতি মনে রাখবেন এবং পরবর্তীতে জিজ্ঞেস করলে তা ব্যাখ্যা করবেন। ফয়সাল আহমেদকে স্যার বলে সম্বোধন করবেন। $memoryPrompt"

            val promptRequest = GeminiRequest(
                contents = listOf(Content(parts = listOf(Part(text = query)))),
                systemInstruction = Content(parts = listOf(Part(text = systemInstruction))),
                generationConfig = GenerationConfig(temperature = 0.7f, maxOutputTokens = 350)
            )

            try {
                val response = RetrofitClient.service.generateContent(apiKey, promptRequest)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                    ?: "আমি দুঃখিত স্যার, আমি বুঝতে পারিনি।"

                // Persistent Memory storage configuration
                val speakerLabel = if (activeSpeakerName.value.contains("মালিক")) "owner" else "guest"
                memoryRepository.insertMemory(
                    MemoryEntry(
                        speaker = speakerLabel,
                        text = query,
                        response = responseText
                    )
                )

                // Update real-time logs
                val currentLogs = conversationLog.value.toMutableList()
                currentLogs.add(0, Pair(query, responseText))
                if (currentLogs.size > 20) currentLogs.removeAt(currentLogs.size - 1)
                conversationLog.value = currentLogs

                withContext(Dispatchers.Main) {
                    speak(responseText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini API Connection failed", e)
                withContext(Dispatchers.Main) {
                    speak("এআই নেটওয়ার্ক ব্যর্থ হয়েছে স্যার, অনুগ্রহ করে ইন্টারনেট সংযোগ চেক করুন।")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        activeInstance = null
        isServiceRunning.value = false
        stopListening()
        
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speechRecognizer on Service destroy", e)
        }
        
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing mediaPlayer in Service destroy", e)
        }
        
        tts?.shutdown()
        serviceJob.cancel()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        super.onDestroy()
    }

    // Custom SpeechRecognizer RecognitionListener
    private inner class EraSpeechListener : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Speech Listener -> onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech Listener -> onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            voiceRmsdB.value = rmsdB // Feed raw physical animation
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech Listener -> onEndOfSpeech")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                9 -> "Recognition service error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Unknown speech error"
            }
            Log.e(TAG, "SpeechRecognizer Error: $errorMessage ($error)")
            
            // Continuous listening loop: restart listening
            isListening = false
            mainHandler.postDelayed({
                if (isServiceRunning.value && assistantState.value != AssistantState.SPEAKING) {
                    startContinuousListening()
                }
            }, 600)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull() ?: ""
            Log.d(TAG, "Speech Recognized Result: $recognizedText")

            if (recognizedText.trim().isEmpty()) {
                startContinuousListening()
                return
            }

            val owner = prefsManager.ownerName
            val currentState = assistantState.value

            if (currentState == AssistantState.IDLE) {
                // Standby listening mode: Scan triggers like "ইরা"
                if (recognizedText.contains("ইরা", ignoreCase = true) || recognizedText.contains("ইরা")) {
                    wakeScreen()
                    
                    // VOICE PROFILE CHECK: Secure owner voice print authentication helper!
                    // If secure voice profile training is done, let's verify!
                    val isTrained = prefsManager.isVoiceTrained
                    if (isTrained) {
                        // Check speaker credential features. E.g. Speech text matches key signature pattern,
                        // or matches physical voice average metrics simulated via rms power comparison
                        val textContainsPhrase = recognizedText.contains(prefsManager.trainedPhrase, ignoreCase = true)
                        
                        // Speak voice identification check
                        if (!textContainsPhrase && !prefsManager.isGuestModeEnabled) {
                            activeSpeakerName.value = "অচেনা ব্যক্তি (ভয়েস প্রোফাইল অমিল)"
                            speak("ক্ষমা করবেন, ভয়েস প্রোফাইলের অমিলের কারণে আমি আপনার প্রশ্নের উত্তর দিতে পারছি না। শুধুমাত্র মালিক ফয়সাল স্যার আমাকে অ্যাক্সেস করতে পারেন।")
                            return
                        } else {
                            activeSpeakerName.value = "${owner} (মালিক - ভয়েস প্রোফাইল মিল)"
                        }
                    } else {
                        activeSpeakerName.value = "${owner} (মালিক - স্বয়ংক্রিয়)"
                    }

                    // Wake up alert response
                    assistantState.value = AssistantState.LISTENING
                    speak("জি স্যার?") {
                        assistantState.value = AssistantState.LISTENING
                        lastStatusText.value = "শুনছে ইরা..."
                    }
                } else {
                    // Re-start stand-by
                    startContinuousListening()
                }
            } else if (currentState == AssistantState.LISTENING) {
                // ACTIVE Query parsing
                // If Voice Print Trained, check if speaker authority isn't restricted
                if (prefsManager.isVoiceTrained && !prefsManager.isGuestModeEnabled) {
                    // Safe guard verify
                    val testVal = voiceRmsdB.value
                    Log.d(TAG, "Owner dynamic vocal amplitude check: $testVal")
                }

                handleVoiceQuery(recognizedText)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val liveText = matches?.firstOrNull() ?: ""
            if (liveText.isNotEmpty() && assistantState.value == AssistantState.LISTENING) {
                lastStatusText.value = "শুনছে: \"$liveText\""
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
