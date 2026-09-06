package com.jarvis.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

        loadInitialConversation()
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
        binding.historyList.removeAllViews()

        val userId = currentUserId ?: return
        db.collection("users").document(userId).collection("conversations")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                for (doc in result) {
                    val title = doc.getString("title") ?: "New chat"
                    val convId = doc.id

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

                    binding.historyList.addView(item)
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

        val whatsappRequest = detectWhatsappCommand(message)
        if (whatsappRequest != null) {
            handleWhatsappCommand(whatsappRequest.first, whatsappRequest.second)
            return
        }

        val openAppName = detectOpenAppCommand(message)
        if (openAppName != null) {
            handleOpenAppCommand(openAppName)
            return
        }

        val playQuery = detectPlayCommand(message)
        if (playQuery != null) {
            handlePlayCommand(playQuery)
            return
        }

        val callName = detectCallCommand(message)
        if (callName != null) {
            handleCallCommand(callName)
            return
        }

        val reminder = detectReminderCommand(message)
        if (reminder != null) {
            handleReminderCommand(reminder.first, reminder.second)
            return
        }

        val timerMinutes = detectTimerCommand(message)
        if (timerMinutes != null) {
            handleReminderCommand("Timer's up!", timerMinutes)
            return
        }

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

    private fun detectOpenAppCommand(message: String): String? {
        val lower = message.lowercase().trim()
        val triggers = listOf("open ", "launch ", "start ")
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                var appName = lower.removePrefix(trigger).trim()
                appName = appName.removeSuffix(" now").removeSuffix(" please").trim()
                if (appMap.containsKey(appName)) {
                    return appName
                }
            }
        }
        return null
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

    private fun detectPlayCommand(message: String): String? {
        val lower = message.lowercase().trim()
        val triggers = listOf("play ")
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                return message.substring(trigger.length).trim()
            }
        }
        return null
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

    private fun detectCallCommand(message: String): String? {
        val lower = message.lowercase().trim()
        val triggers = listOf("call ", "phone ", "dial ")
        for (trigger in triggers) {
            if (lower.startsWith(trigger)) {
                return message.substring(trigger.length).trim()
            }
        }
        return null
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

    private fun detectWhatsappCommand(message: String): Pair<String, String>? {
        val lower = message.lowercase().trim()
        val patterns = listOf(
            Regex("^whatsapp (.+?) saying (.+)$"),
            Regex("^message (.+?) on whatsapp saying (.+)$"),
            Regex("^text (.+?) on whatsapp saying (.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val text = match.groupValues[2].trim()
                return Pair(name, text)
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

    private fun detectReminderCommand(message: String): Pair<String, Int>? {
        val lower = message.lowercase().trim()
        val pattern = Regex("^remind me to (.+?) in (\\d+) (minute|minutes|hour|hours)$")
        val match = pattern.find(lower) ?: return null
        val task = match.groupValues[1].trim()
        val amount = match.groupValues[2].toIntOrNull() ?: return null
        val unit = match.groupValues[3]
        val minutes = if (unit.startsWith("hour")) amount * 60 else amount
        return Pair(task, minutes)
    }

    private fun detectTimerCommand(message: String): Int? {
        val lower = message.lowercase().trim()
        val pattern = Regex("^set a timer for (\\d+) (minute|minutes|second|seconds)$")
        val match = pattern.find(lower) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        return if (unit.startsWith("second")) 0 else amount
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
                val json = JSONObject().put("message", message).toString()
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
        super.onDestroy()
    }
}