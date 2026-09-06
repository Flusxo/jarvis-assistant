package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.jarvis.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var prefs: SharedPreferences
    private val micPermissionCode = 100
    private val speechRequestCode = 200
    private val contactsCallPermissionCode = 400
    private val notificationPermissionCode = 500
    private var pendingCallName: String? = null
    private val relayUrl = "https://jarvis-relay.ishimweshiza324.workers.dev"
    private val httpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()
    private val dotAnimators = mutableListOf<ObjectAnimator>()
    private val db = FirebaseFirestore.getInstance()
    private val currentUserId get() = FirebaseAuth.getInstance().currentUser?.uid
    private var currentConversationId: String? = null

    // --- New: personality / tone setting ---
    private val personalityOptions = listOf("Friendly", "Formal", "Sarcastic", "Concise")
    private val personality get() = prefs.getString("personality", "Friendly") ?: "Friendly"

    // --- New: flashlight state (toggled via voice command) ---
    private var flashlightOn = false

    // --- New: cache of (title, id) pairs so the history search box can filter locally
    // without re-hitting Firestore on every keystroke ---
    private val historyCache = mutableListOf<Pair<String, String>>()

    private val appMap = mapOf(
        "instagram" to "com.instagram.android",
        "youtube" to "com.google.android.youtube",
        "snapchat" to "com.snapchat.android",
        "whatsapp" to "com.whatsapp",
        "spotify" to "com.spotify.music",
        "settings" to "com.android.settings"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        binding.sendButton.setOnClickListener {
            val message = binding.inputText.text.toString()
            if (message.isNotBlank()) {
                handleUserMessage(message)
                binding.inputText.text.clear()
            }
        }

        binding.micButton.setOnClickListener {
            checkMicPermissionAndListen()
        }

        binding.historyButton.setOnClickListener {
            openHistoryPanel()
        }

        binding.closeHistoryButton.setOnClickListener {
            binding.historyPanel.visibility = View.GONE
        }

        binding.newChatButton.setOnClickListener {
            startNewChat()
            binding.historyPanel.visibility = View.GONE
        }

        binding.suggestion1.setOnClickListener { handleUserMessage(binding.suggestion1.text.toString()) }
        binding.suggestion2.setOnClickListener { handleUserMessage(binding.suggestion2.text.toString()) }
        binding.suggestion3.setOnClickListener { handleUserMessage(binding.suggestion3.text.toString()) }

        binding.conversationTitle.setOnLongClickListener {
            showPersonalityDialog()
            true
        }

        binding.historySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderHistoryList(s?.toString().orEmpty())
            }
        })

        loadInitialConversation()
        maybeShowDailyBriefing()
    }

    private fun showPersonalityDialog() {
        val current = personalityOptions.indexOf(personality).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Jarvis personality")
            .setSingleChoiceItems(personalityOptions.toTypedArray(), current) { dialog, which ->
                prefs.edit().putString("personality", personalityOptions[which]).apply()
                Toast.makeText(this, "Jarvis is now ${personalityOptions[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Shows one AI-generated greeting bubble the first time the app is opened each day. */
    private fun maybeShowDailyBriefing() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val lastShown = prefs.getString("last_briefing_date", null)
        if (lastShown == today) return
        prefs.edit().putString("last_briefing_date", today).apply()

        CoroutineScope(Dispatchers.Main).launch {
            val prompt = "Give me a short, one or two sentence daily greeting to start my day. " +
                "Be warm but brief. Do not ask a question."
            val reply = getAiReply(prompt)
            hideEmptyState()
            addBubble(reply, isUser = false, saveToHistory = false)
        }
    }

    private fun loadInitialConversation() {
        val savedId = prefs.getString("current_conversation_id", null)
        if (savedId != null) {
            currentConversationId = savedId
            loadMessagesFor(savedId)
            return
        }

        val userId = currentUserId ?: return
        db.collection("users").document(userId).collection("conversations")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { result ->
                if (!result.isEmpty) {
                    val convId = result.documents[0].id
                    currentConversationId = convId
                    prefs.edit().putString("current_conversation_id", convId).apply()
                    loadMessagesFor(convId)
                }
            }
    }

    private fun startNewChat() {
        binding.chatContainer.removeAllViews()
        currentConversationId = null
        prefs.edit().remove("current_conversation_id").apply()
        showEmptyState()
    }

    private fun showEmptyState() {
        binding.emptyStateContainer.visibility = View.VISIBLE
        binding.chatScroll.visibility = View.GONE
    }

    private fun hideEmptyState() {
        binding.emptyStateContainer.visibility = View.GONE
        binding.chatScroll.visibility = View.VISIBLE
    }

    private fun ensureConversation(onReady: (String) -> Unit) {
        val existing = currentConversationId
        if (existing != null) {
            onReady(existing)
            return
        }
        val userId = currentUserId ?: return
        val newConvo = hashMapOf(
            "title" to "New chat",
            "createdAt" to System.currentTimeMillis(),
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("users").document(userId).collection("conversations")
            .add(newConvo)
            .addOnSuccessListener { docRef ->
                currentConversationId = docRef.id
                prefs.edit().putString("current_conversation_id", docRef.id).apply()
                onReady(docRef.id)
            }
    }

    private fun openHistoryPanel() {
        binding.historyPanel.visibility = View.VISIBLE
        binding.historySearch.setText("")

        val userId = currentUserId ?: return
        db.collection("users").document(userId).collection("conversations")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                historyCache.clear()
                for (doc in result) {
                    val title = doc.getString("title") ?: "New chat"
                    historyCache.add(Pair(title, doc.id))
                }
                renderHistoryList("")
            }
    }

    /** Re-renders the history list from historyCache, optionally filtered by a search query. */
    private fun renderHistoryList(filter: String) {
        binding.historyList.removeAllViews()
        val query = filter.trim().lowercase()
        val visible = if (query.isEmpty()) {
            historyCache
        } else {
            historyCache.filter { it.first.lowercase().contains(query) }
        }

        for ((title, convId) in visible) {
            val item = TextView(this)
            item.text = title
            item.setTextColor(0xFFF2F2F5.toInt())
            item.textSize = 14f
            item.setPadding(17, 28, 17, 28)
            item.setBackgroundResource(R.drawable.bg_input)
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 10
            item.layoutParams = params

            item.setOnClickListener {
                currentConversationId = convId
                prefs.edit().putString("current_conversation_id", convId).apply()
                binding.chatContainer.removeAllViews()
                loadMessagesFor(convId)
                binding.historyPanel.visibility = View.GONE
            }

            item.setOnLongClickListener {
                confirmDeleteConversation(title, convId)
                true
            }

            binding.historyList.addView(item)
        }
    }

    private fun confirmDeleteConversation(title: String, convId: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete chat?")
            .setMessage("\"$title\" will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ -> deleteConversation(convId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteConversation(convId: String) {
        val userId = currentUserId ?: return
        val convoRef = db.collection("users").document(userId).collection("conversations").document(convId)

        convoRef.collection("messages").get().addOnSuccessListener { messages ->
            val batch = db.batch()
            for (doc in messages) batch.delete(doc.reference)
            batch.delete(convoRef)
            batch.commit().addOnSuccessListener {
                historyCache.removeAll { it.second == convId }
                renderHistoryList(binding.historySearch.text.toString())

                if (currentConversationId == convId) {
                    startNewChat()
                }
            }
        }
    }

    private fun loadMessagesFor(conversationId: String) {
        val userId = currentUserId ?: return
        db.collection("users").document(userId)
            .collection("conversations").document(conversationId)
            .collection("messages")
            .orderBy("timestamp")
            .get()
            .addOnSuccessListener { result ->
                if (!result.isEmpty) {
                    hideEmptyState()
                }
                for (doc in result) {
                    val text = doc.getString("text") ?: continue
                    val isUser = doc.getBoolean("isUser") ?: false
                    addBubble(text, isUser, saveToHistory = false)
                }
            }
    }

    private fun handleUserMessage(message: String) {
        hideEmptyState()
        addBubble(message, isUser = true)

        when (val command = CommandParser.parse(message, appMap)) {
            is ParsedCommand.WhatsApp -> handleWhatsappCommand(command.name, command.text)
            is ParsedCommand.Sms -> handleSmsCommand(command.name, command.text)
            is ParsedCommand.OpenApp -> handleOpenAppCommand(command.appName)
            is ParsedCommand.Play -> handlePlayCommand(command.query)
            is ParsedCommand.Call -> handleCallCommand(command.name)
            is ParsedCommand.Flashlight -> handleFlashlightCommand(command.turnOn)
            is ParsedCommand.WifiPanel -> handleWifiPanelCommand()
            is ParsedCommand.BluetoothPanel -> handleBluetoothPanelCommand()
            is ParsedCommand.ReminderAbsolute -> handleReminderAbsoluteCommand(command.task, command.hour, command.minute)
            is ParsedCommand.ReminderRelative -> handleReminderCommand(command.task, command.minutesFromNow)
            is ParsedCommand.Timer -> handleReminderCommand("Timer's up!", command.minutesFromNow)
            is ParsedCommand.Note -> handleNoteCommand(command.text)
            is ParsedCommand.ListNotes -> handleListNotesCommand()
            is ParsedCommand.None -> {
                binding.statusText.text = "Thinking..."
                showThinkingDots()

                CoroutineScope(Dispatchers.Main).launch {
                    val reply = getAiReply(message)
                    hideThinkingDots()
                    addBubble(reply, isUser = false)
                    binding.statusText.text = "Online"
                    speak(reply)
                }
            }
        }
    }

    // --- New command handlers ---

    private fun handleSmsCommand(name: String, text: String) {
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) {
            val reply = "I need contacts permission to text $name. Try asking me to call someone first to grant it."
            addBubble(reply, isUser = false)
            speak(reply)
            return
        }

        val number = findPhoneNumber(name)
        if (number == null) {
            val reply = "I couldn't find a contact named $name."
            addBubble(reply, isUser = false)
            speak(reply)
            return
        }

        val reply = "Opening a text to $name."
        addBubble(reply, isUser = false)
        speak(reply)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", text)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = "I couldn't find a messaging app to send that with."
            addBubble(fallback, isUser = false)
            speak(fallback)
        }
    }

    private fun handleFlashlightCommand(turnOn: Boolean) {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                val reply = "This device doesn't seem to have a flashlight."
                addBubble(reply, isUser = false)
                speak(reply)
                return
            }
            cameraManager.setTorchMode(cameraId, turnOn)
            flashlightOn = turnOn
            val reply = if (turnOn) "Flashlight on." else "Flashlight off."
            addBubble(reply, isUser = false)
            speak(reply)
        } catch (e: Exception) {
            val reply = "I couldn't control the flashlight on this device."
            addBubble(reply, isUser = false)
            speak(reply)
        }
    }

    private fun handleWifiPanelCommand() {
        val reply = "Here's the Wi-Fi panel."
        addBubble(reply, isUser = false)
        speak(reply)
        try {
            startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun handleBluetoothPanelCommand() {
        val reply = "Here's Bluetooth settings."
        addBubble(reply, isUser = false)
        speak(reply)
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun handleReminderAbsoluteCommand(task: String, hour: Int, minute: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionCode)
            }
        }

        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }

        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("message", task)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, target.timeInMillis, pendingIntent)

        val timeLabel = SimpleDateFormat("h:mm a", Locale.US).format(target.time)
        val reply = "Got it, I'll remind you to $task at $timeLabel."
        addBubble(reply, isUser = false)
        speak(reply)
    }

    private fun handleNoteCommand(text: String) {
        val userId = currentUserId
        if (userId == null) {
            val reply = "I need you to be signed in to save notes."
            addBubble(reply, isUser = false)
            speak(reply)
            return
        }
        val note = hashMapOf("text" to text, "timestamp" to System.currentTimeMillis())
        db.collection("users").document(userId).collection("notes").add(note)

        val reply = "Noted."
        addBubble(reply, isUser = false)
        speak(reply)
    }

    private fun handleListNotesCommand() {
        val userId = currentUserId
        if (userId == null) {
            addBubble("I need you to be signed in to see notes.", isUser = false)
            return
        }
        db.collection("users").document(userId).collection("notes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(5)
            .get()
            .addOnSuccessListener { result ->
                val reply = if (result.isEmpty) {
                    "You don't have any notes yet."
                } else {
                    "Your recent notes:\n" + result.documents.joinToString("\n") { doc ->
                        "\u2022 ${doc.getString("text") ?: ""}"
                    }
                }
                addBubble(reply, isUser = false)
                speak("Here are your recent notes.")
            }
    }

    private fun handleOpenAppCommand(appName: String) {
        val packageName = appMap[appName]
        val launchIntent = packageName?.let { packageManager.getLaunchIntentForPackage(it) }

        if (launchIntent != null) {
            val reply = "Opening $appName."
            addBubble(reply, isUser = false)
            speak(reply)
            startActivity(launchIntent)
        } else {
            val reply = "I couldn't find $appName installed on this device."
            addBubble(reply, isUser = false)
            speak(reply)
        }
    }

    private fun handlePlayCommand(query: String) {
        val reply = "Playing $query."
        addBubble(reply, isUser = false)
        speak(reply)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        }
        startActivity(intent)
    }

    private fun handleCallCommand(name: String) {
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasCall = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        if (!hasContacts || !hasCall) {
            pendingCallName = name
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE),
                contactsCallPermissionCode
            )
            return
        }

        placeCallTo(name)
    }

    private fun placeCallTo(name: String) {
        val number = findPhoneNumber(name)
        if (number == null) {
            val reply = "I couldn't find a contact named $name."
            addBubble(reply, isUser = false)
            speak(reply)
            return
        }

        val reply = "Calling $name."
        addBubble(reply, isUser = false)
        speak(reply)

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
        }
        startActivity(intent)
    }

    private fun findPhoneNumber(name: String): String? {
        val resolver = contentResolver
        val cursor = resolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                return it.getString(numberIndex)
            }
        }
        return null
    }

    private fun handleWhatsappCommand(name: String, text: String) {
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) {
            val reply = "I need contacts permission to message $name. Try asking me to call someone first to grant it."
            addBubble(reply, isUser = false)
            speak(reply)
            return
        }

        val number = findPhoneNumber(name)
        if (number == null) {
            val reply = "I couldn't find a contact named $name."
            addBubble(reply, isUser = false)
            speak(reply)
            return
        }

        val reply = "Opening WhatsApp chat with $name."
        addBubble(reply, isUser = false)
        speak(reply)

        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$cleanNumber?text=${Uri.encode(text)}")
        }
        startActivity(intent)
    }

    private fun handleReminderCommand(task: String, minutesFromNow: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionCode)
            }
        }

        val triggerTime = System.currentTimeMillis() + (minutesFromNow * 60 * 1000L)
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("message", task)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)

        val reply = if (minutesFromNow > 0) {
            "Got it, I'll remind you to $task in $minutesFromNow minute${if (minutesFromNow != 1) "s" else ""}."
        } else {
            "Got it, timer set."
        }
        addBubble(reply, isUser = false)
        speak(reply)
    }

    private fun showThinkingDots() {
        binding.thinkingDots.visibility = View.VISIBLE
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        dots.forEachIndexed { index, dot ->
            val animator = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f, 0.3f).apply {
                duration = 900
                startDelay = index * 150L
                repeatCount = ValueAnimator.INFINITE
            }
            animator.start()
            dotAnimators.add(animator)
        }
    }

    private fun hideThinkingDots() {
        dotAnimators.forEach { it.cancel() }
        dotAnimators.clear()
        binding.thinkingDots.visibility = View.GONE
    }

    private suspend fun getAiReply(message: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject()
                    .put("message", message)
                    .put("tone", personality)
                    .toString()
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(relayUrl).post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: return@use "Sorry, no response."
                    JSONObject(responseBody).optString("reply", "Sorry, I couldn't think of a reply.")
                }
            } catch (e: Exception) {
                "Sorry, I couldn't reach my brain right now. Check your internet connection. (${e.javaClass.simpleName})"
            }
        }
    }

    private fun addBubble(text: String, isUser: Boolean, saveToHistory: Boolean = true) {
        val bubbleView = layoutInflater.inflate(
            if (isUser) R.layout.bubble_user else R.layout.bubble_jarvis,
            binding.chatContainer,
            false
        )
        if (isUser) {
            (bubbleView as TextView).text = text
        } else {
            bubbleView.findViewById<TextView>(R.id.bubbleText).text = text
        }
        binding.chatContainer.addView(bubbleView)

        binding.chatScroll.post {
            binding.chatScroll.fullScroll(View.FOCUS_DOWN)
        }

        if (saveToHistory) {
            ensureConversation { convId ->
                saveMessage(convId, text, isUser)
            }
        }
    }

    private fun saveMessage(conversationId: String, text: String, isUser: Boolean) {
        val userId = currentUserId ?: return
        val convoRef = db.collection("users").document(userId)
            .collection("conversations").document(conversationId)

        val message = hashMapOf(
            "text" to text,
            "isUser" to isUser,
            "timestamp" to System.currentTimeMillis()
        )
        convoRef.collection("messages").add(message)
        convoRef.update("updatedAt", System.currentTimeMillis())

        if (isUser) {
            convoRef.get().addOnSuccessListener { doc ->
                val currentTitle = doc.getString("title")
                if (currentTitle == null || currentTitle == "New chat") {
                    val newTitle = if (text.length > 40) text.take(40) + "..." else text
                    convoRef.update("title", newTitle)
                    binding.conversationTitle.text = newTitle
                }
            }
        }
    }

    private fun checkMicPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), micPermissionCode
            )
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            micPermissionCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                } else {
                    Toast.makeText(this, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show()
                }
            }
            contactsCallPermissionCode -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                val name = pendingCallName
                pendingCallName = null
                if (allGranted && name != null) {
                    placeCallTo(name)
                } else {
                    Toast.makeText(this, "Contacts and phone permission are needed to make calls", Toast.LENGTH_SHORT).show()
                }
            }
            notificationPermissionCode -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Notification permission is needed for reminders to show", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Jarvis...")
        }
        binding.statusText.text = "Listening..."
        try {
            startActivityForResult(intent, speechRequestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show()
            binding.statusText.text = "Online"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequestCode && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0)
            if (!spokenText.isNullOrBlank()) {
                handleUserMessage(spokenText)
            } else {
                binding.statusText.text = "Online"
            }
        } else {
            binding.statusText.text = "Online"
        }
    }

    private fun speak(text: String) {
        binding.statusText.text = "Speaking..."
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        binding.root.postDelayed({ binding.statusText.text = "Online" }, 1500)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        dotAnimators.forEach { it.cancel() }
        tts.stop()
        tts.shutdown()
        if (flashlightOn) {
            try {
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                cameraId?.let { cameraManager.setTorchMode(it, false) }
            } catch (e: Exception) { /* best effort cleanup */ }
        }
        super.onDestroy()
    }
}