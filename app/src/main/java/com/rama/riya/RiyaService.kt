package com.rama.riya

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiyaService : Service() {

    data class Contact(val name: String, val number: String)

    // Used when a contact number has no country code (change if you are not in India)
    private val defaultCountryCode = "91"

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    @Volatile private var busy = false
    private var followUp = false
    private var history = JSONArray()
    private var lastAgentTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    // When Riya asks "whom?", the next sentence is the answer (no wake word needed)
    private var pendingContacts: List<Contact>? = null
    private var pendingAction: ((Contact) -> Unit)? = null
    private val clearPending = Runnable { pendingContacts = null; pendingAction = null }

    // --- recognizer state ---
    private var listening = false
    private var listenStart = 0L
    private var speakStart = 0L
    private var busyStart = 0L
    private var beepMuted = false
    private val mutedStreams = mutableListOf<Int>()
    private val restartRunnable = Runnable { startListening() }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (speaking && now - speakStart > 20000) speaking = false
            if (busy && now - busyStart > 70000) busy = false
            if (listening && now - listenStart > 20000) { listening = false; resetRecognizer() }
            if (!listening && !speaking && !busy) startListening()
            handler.postDelayed(this, 5000)
        }
    }

    /** The "ding" the Google recogniser plays on every listen cycle. Mute it while listening, unmute before speaking. */
    private fun muteBeep(mute: Boolean) {
        if (mute == beepMuted) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (mute) {
                mutedStreams.clear()
                mutedStreams.add(AudioManager.STREAM_SYSTEM)
                if (!am.isMusicActive) mutedStreams.add(AudioManager.STREAM_MUSIC)  // don't kill music that is playing
                // add AudioManager.STREAM_NOTIFICATION here if you still hear a beep (it also silences notification sounds)
                mutedStreams.forEach { am.adjustStreamVolume(it, AudioManager.ADJUST_MUTE, 0) }
            } else {
                mutedStreams.forEach { am.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0) }
                mutedStreams.clear()
            }
            beepMuted = mute
        } catch (e: Exception) { beepMuted = false }
    }

    private var assistantName = "riya"
    private var variants = listOf("riya")

    private fun loadWakeName() {
        assistantName = getSharedPreferences("riya", Context.MODE_PRIVATE)
            .getString("name", "riya")!!.trim().lowercase().ifEmpty { "riya" }
        variants = if (assistantName == "riya") listOf("riya", "ria", "reya", "rhea", "riyah", "రియా", "రియ", "రీయా")
                   else listOf(assistantName)
    }

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    private fun norm(x: String) = x.lowercase().filter { !it.isWhitespace() && it !in ",.!?;:'\"-" }

    /** Returns the command after the wake name, or null if the name was not heard.
     *  Tolerates mis-hearings and names split into two words ("man isha"). */
    private fun extractCommand(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val targets = variants.map { norm(it) }.filter { it.isNotEmpty() }
        val maxSpan = variants.maxOf { it.trim().split(Regex("\\s+")).size } + 1
        for (idx in words.indices) {
            for (span in 1..maxSpan) {
                if (idx + span > words.size) break
                val cand = norm(words.subList(idx, idx + span).joinToString(""))
                if (cand.isEmpty()) continue
                val hit = targets.any { t ->
                    val tol = if (assistantName == "riya") 0 else if (t.length >= 7) 2 else if (t.length >= 4) 1 else 0
                    lev(cand, t) <= tol
                }
                if (hit) return words.drop(idx + span).joinToString(" ")
            }
        }
        return null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "WAKE") {
            handler.postDelayed({
                awaitingCommand = true
                handler.removeCallbacks(clearAwait)
                handler.postDelayed(clearAwait, 12000)
                say("Yes, I'm listening")
            }, 700)
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        loadWakeName()
        getSharedPreferences("riya", Context.MODE_PRIVATE).edit().putBoolean("running", true).apply()
        val channelId = "riya_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Riya", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName is listening")
            .setContentText("Say \"$assistantName\" followed by a command")
            .setSmallIcon(R.drawable.ic_mic)
            .setColor(0xFF22D3C5.toInt())
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        // Keep the CPU awake so Riya keeps listening when the screen is off / locked
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya:listen").apply { acquire() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { handler.post { speaking = false; startListening() } }
            override fun onDone(utteranceId: String?) {
                handler.post {
                    speaking = false
                    if (followUp) {
                        followUp = false
                        awaitingCommand = true
                        handler.removeCallbacks(clearAwait)
                        handler.postDelayed(clearAwait, 9000)
                    }
                    startListening()
                }
            }
        })
        startListening()
        handler.postDelayed(watchdog, 5000)
    }

    // ---------- Listening ----------

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase() } ?: emptyList()
            if (alts.isNotEmpty()) {
                getSharedPreferences("riya", Context.MODE_PRIVATE).edit().putString("heard", alts.first()).apply()
                handleSpeech(alts)
            }
            restart(100)
        }
        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> toastOnce("Microphone permission missing. Tap button 1 in the app.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> toastOnce("Offline? Download the offline speech pack: Settings > Google > Voice > Offline speech recognition")
                SpeechRecognizer.ERROR_AUDIO -> toastOnce("Microphone error. Close other apps using the mic.")
                12, 13 -> toastOnce("Speech language not supported / not downloaded")
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> { resetRecognizer(); restart(800) }
                else -> { resetRecognizer(); restart(1500) }
            }
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun resetRecognizer() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
    }

    private fun startListening() {
        handler.removeCallbacks(restartRunnable)
        if (!running || speaking || busy || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toastOnce("Speech recognition not available. Install/enable the Google app and Google voice typing.")
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang() == "te") "te-IN" else "en-IN")
            if (Build.VERSION.SDK_INT >= 33 && lang() == "te")
                putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("en-IN"))  // so an English wake name is still heard
            if (!isOnline()) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        muteBeep(true)
        listening = true
        listenStart = System.currentTimeMillis()
        try { rec.startListening(intent) } catch (e: Exception) { listening = false; resetRecognizer(); restart(1000) }
    }

    private fun restart(delayMs: Long = 400) {
        if (!running || speaking || busy) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private var lastToast = 0L
    private fun toastOnce(msg: String) {
        if (System.currentTimeMillis() - lastToast < 15000) return
        lastToast = System.currentTimeMillis()
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------- Speaking ----------

    private fun say(msg: String) {
        val block = Runnable {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            if (ttsReady) {
                val isTelugu = msg.any { it in '\u0C00'..'\u0C7F' }
                val r = tts?.setLanguage(if (isTelugu) Locale("te", "IN") else Locale("en", "IN"))
                if (r == null || r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    toastOnce("Install this language voice: Settings > Text-to-speech")
                    restart()
                } else {
                    muteBeep(false)
                    speaking = true
                    speakStart = System.currentTimeMillis()
                    listening = false
                    recognizer?.cancel()
                    val ok = tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "riya_utt")
                    if (ok != TextToSpeech.SUCCESS) { speaking = false; restart() }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block.run() else handler.post(block)
    }

    // ---------- Command handling ----------

    // After the name alone is said ("hey siri"), the next sentence is the command (no name needed)
    private var awaitingCommand = false
    private val clearAwait = Runnable { awaitingCommand = false }

    private fun handleSpeech(alts: List<String>) {
        val pending = pendingContacts
        if (pending != null) {
            handleChoice(alts.first(), pending)
            return
        }
        if (awaitingCommand) {
            awaitingCommand = false
            handler.removeCallbacks(clearAwait)
            val text = alts.first()
            if (Regex("^(thanks|thank you|that's all|that is all|bye|goodbye|never mind|nevermind|ok thanks|okay thanks|stop)\\b").containsMatchIn(text)) {
                say("Okay")
                return
            }
            val cmd = extractCommand(text)?.takeIf { it.isNotEmpty() } ?: text
            dispatch(cmd)
            return
        }
        for (text in alts) {
            val command = extractCommand(text) ?: continue
            if (command.isEmpty()) {
                awaitingCommand = true
                handler.removeCallbacks(clearAwait)
                handler.postDelayed(clearAwait, 12000)
                say("Yes, I'm listening")
                return
            }
            dispatch(command)
            return
        }
    }

    private fun apiKey() = getSharedPreferences("riya", Context.MODE_PRIVATE).getString("apikey", "")!!.trim()

    /** With an API key the AI agent understands natural speech; without one the simple keyword commands are used. */
    private fun dispatch(cmd: String) {
        muteBeep(false)
        val c = teToEn(cmd)
        if (c.startsWith("stop") || c.startsWith("exit")) handle(c)
        else if (apiKey().isBlank() || !isOnline()) handle(c)   // offline: built-in commands
        else runAgent(cmd)
    }

    private fun lang() = getSharedPreferences("riya", Context.MODE_PRIVATE).getString("lang", "en")!!

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------- Telugu (offline keyword support) ----------
    private val teMap = linkedMapOf(
        "ఫ్లాష్‌లైట్" to "flashlight", "టార్చ్" to "torch", "లాక్" to "lock", "వాల్యూమ్" to "volume",
        "పెంచు" to "up", "పెంచండి" to "up", "తగ్గించు" to "down", "తగ్గించండి" to "down", "మ్యూట్" to "mute",
        "కెమెరా" to "camera", "ప్లే" to "play", "సమయం" to "time", "టైమ్" to "time", "తేదీ" to "date", "ఆపు" to "stop"
    )
    private val appAliases = mapOf(
        "వాట్సాప్" to "whatsapp", "యూట్యూబ్" to "youtube", "కెమెరా" to "camera", "గూగుల్" to "google",
        "ఫోన్" to "phone", "సెట్టింగ్స్" to "settings", "గ్యాలరీ" to "gallery", "మ్యాప్స్" to "maps",
        "ఇన్స్టాగ్రామ్" to "instagram", "ఫేస్బుక్" to "facebook", "టెలిగ్రామ్" to "telegram",
        "క్రోమ్" to "chrome", "స్పాటిఫై" to "spotify", "ఫోన్‌పే" to "phonepe", "గూగుల్ పే" to "google pay",
        "కాలిక్యులేటర్" to "calculator", "స్నాప్‌చాట్" to "snapchat", "అమెజాన్" to "amazon", "ఫ్లిప్‌కార్ట్" to "flipkart", "జీమెయిల్" to "gmail", "డ్రైవ్" to "drive", "ఫోటోలు" to "photos", "నోట్స్" to "notes", "ప్లే స్టోర్" to "play store", "గడియారం" to "clock", "మెసేజెస్" to "messages"
    )

    private fun teToEn(t: String): String {
        if (t.none { it in '\u0C00'..'\u0C7F' }) return t
        Regex("^(.+?)\\s*(?:ఓపెన్|తెరువు|తెరవండి|తెరవు|స్టార్ట్).*$").find(t)?.let { return "open " + it.groupValues[1].trim().let { n -> appAliases[n] ?: n } }
        Regex("^(?:ఓపెన్)\\s+(.+)$").find(t)?.let { return "open " + it.groupValues[1].trim().let { n -> appAliases[n] ?: n } }
        Regex("^(.+?)\\s*(?:కి|కు|కే)?\\s*(?:కాల్|ఫోన్).*$").find(t)?.let { return "call " + it.groupValues[1].trim() }
        var s = t
        for ((k, v) in teMap) s = s.replace(k, " $v ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Opens ANY installed app by its name (works offline). */
    private fun openAppByName(raw: String): String {
        var q = raw.lowercase().trim().removePrefix("the ").removeSuffix(" app").trim()
        appAliases[q]?.let { q = it }
        fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        val nq = norm(q)
        if (nq.isEmpty()) return "Which app?"
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        fun score(l: String): Int = when {
            l == nq -> 0
            l.startsWith(nq) -> 1
            l.contains(nq) -> 2
            nq.contains(l) && l.length >= 3 -> 3
            lev(l, nq) <= 1 -> 4
            else -> 99
        }
        val best = apps.map { it to norm(it.loadLabel(pm).toString()) }
            .filter { it.second.isNotEmpty() }.minByOrNull { score(it.second) }
        if (best == null || score(best.second) == 99) return "I could not find an app called $q"
        val li = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
        return if (li != null && launch(li)) "Opening " + best.first.loadLabel(pm) else "Could not open $q"
    }

    private fun handle(c: String) {
        when {
            c.startsWith("stop") || c.startsWith("exit") -> stopSelf()
            Regex("^(open|launch|start) (?!(camera|youtube|spotify)$).+").containsMatchIn(c) ->
                say(openAppByName(c.replace(Regex("^(open|launch|start) "), "")))
            "lock" in c && "unlock" !in c -> lockScreen()
            "flashlight" in c || "torch" in c || "flash light" in c -> flashlight(c)
            "volume" in c || "mute" in c || "louder" in c -> volume(c)
            Regex("\\b(time|clock)\\b").containsMatchIn(c) && !c.startsWith("play") ->
                say("The time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            Regex("\\b(date|today)\\b").containsMatchIn(c) && !c.startsWith("play") ->
                say("Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()))
            "whatsapp" in c || c.startsWith("message") || c.startsWith("send message") -> whatsapp(c)
            c.startsWith("call") || c.startsWith("phone") -> {
                val name = c.replace(Regex("^(call|phone)( to)?"), "").trim()
                if (name.isEmpty()) say("Whom should I call?")
                else pickContact(name) { dial(it) }
            }
            "camera" in c -> openCamera()
            "play" in c -> {
                val m = Regex("play (.+?)(?: on (youtube|spotify))?$").find(c)
                val song = m?.groupValues?.get(1) ?: c.replace("play", "").trim()
                val platform = m?.groupValues?.get(2).orEmpty()
                if (platform == "youtube") youtube(song) else playSpotify(song)
            }
            "youtube" in c -> youtube(null)
            "spotify" in c -> openSpotify()
            else -> say("Sorry, I did not understand")
        }
    }

    // ---------- Contacts: search + ask whom ----------

    private fun findContacts(name: String): List<Contact> {
        val result = LinkedHashMap<String, Contact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val key = num.filter { it.isDigit() }.takeLast(10)
                if (key.isNotEmpty() && !result.containsKey(key)) result[key] = Contact(n, num)
            }
        }
        return result.values.toList()
    }

    private fun pickContact(name: String, action: (Contact) -> Unit) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Please allow contacts permission in the Riya app"); return
        }
        val list = findContacts(name)
        when {
            list.isEmpty() -> say("I could not find $name in your contacts")
            list.size == 1 -> action(list[0])
            else -> {
                val shown = list.take(5)
                pendingContacts = shown
                pendingAction = action
                handler.removeCallbacks(clearPending)
                handler.postDelayed(clearPending, 25000)
                val options = shown.mapIndexed { i, ct -> "${i + 1}, ${labelFor(ct, shown)}" }.joinToString(". ")
                say("I found ${shown.size} contacts named $name. $options. Whom do you want?")
            }
        }
    }

    private fun labelFor(c: Contact, all: List<Contact>): String =
        if (all.count { it.name == c.name } > 1)
            "${c.name}, number ending ${c.number.filter { it.isDigit() }.takeLast(2)}"
        else c.name

    private val numberWords = mapOf(
        "one" to 1, "won" to 1, "first" to 1, "1" to 1,
        "two" to 2, "to" to 2, "too" to 2, "second" to 2, "2" to 2,
        "three" to 3, "third" to 3, "3" to 3,
        "four" to 4, "for" to 4, "fourth" to 4, "4" to 4,
        "five" to 5, "fifth" to 5, "5" to 5
    )

    private fun handleChoice(text: String, list: List<Contact>) {
        if (Regex("\\b(cancel|no|nobody|none|stop)\\b").containsMatchIn(text)) {
            clearPending.run(); handler.removeCallbacks(clearPending)
            say("Okay, cancelled")
            return
        }
        val idx = text.split(" ").mapNotNull { numberWords[it] }.firstOrNull()
        var chosen: Contact? = if (idx != null && idx in 1..list.size) list[idx - 1] else null
        if (chosen == null) {
            val matches = list.filter { text.contains(it.name.lowercase()) || it.name.lowercase().contains(text.trim()) }
            if (matches.size == 1) chosen = matches[0]
        }
        if (chosen != null) {
            val action = pendingAction
            clearPending.run(); handler.removeCallbacks(clearPending)
            action?.invoke(chosen)
        } else {
            say("Sorry, say the number, like one or two")
        }
    }

    // ---------- Call ----------

    private fun dial(c: Contact) {
        say("Calling ${c.name}")
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                // TelecomManager places the call even when the phone is locked
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return
            } catch (e: Exception) { /* fall through */ }
            if (launch(Intent(Intent.ACTION_CALL, uri))) return
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
    }

    // ---------- WhatsApp ----------

    private fun whatsapp(c: String) {
        val cleaned = c.replace(Regex("^(send )?(a )?(whatsapp )?(message )?(on whatsapp )?(to )?"), "").trim()
        val m = Regex("^(.+?) (?:saying|that|message|as) (.+)$").find(cleaned)
        if (m == null) {
            say("Say it like: Riya send WhatsApp to Vinod saying hello")
            return
        }
        val name = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        pickContact(name) { contact ->
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            say("Sending WhatsApp to ${contact.name}")
            RiyaAccessibility.arm()
            val url = "https://wa.me/$digits?text=" + Uri.encode(text)
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ---------- Flashlight ----------

    private fun flashlight(c: String) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { say("No flashlight found"); return }
        torchOn = if ("off" in c) false else if ("on" in c) true else !torchOn
        try {
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("Could not control the flashlight") }
    }

    // ---------- Volume ----------

    private fun volume(c: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stream = AudioManager.STREAM_MUSIC
        val percent = Regex("(\\d{1,3})").find(c)?.groupValues?.get(1)?.toIntOrNull()
        when {
            "unmute" in c -> am.setStreamVolume(stream, max / 2, AudioManager.FLAG_SHOW_UI)
            "mute" in c -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            percent != null -> am.setStreamVolume(stream, (percent.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            "max" in c || "full" in c -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            "up" in c || "increase" in c || "raise" in c || "louder" in c || "high" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            }
            "down" in c || "decrease" in c || "lower" in c || "low" in c || "reduce" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
            else -> { say("Say volume up, down, mute, or a number"); return }
        }
        say("Volume " + (am.getStreamVolume(stream) * 100 / max) + " percent")
    }

    // ================= AI AGENT (Groq) =================

    private fun tool(name: String, desc: String, vararg props: Pair<String, String>,
                     required: List<String> = props.map { it.first }): JSONObject {
        val p = JSONObject()
        props.forEach { (k, t) -> p.put(k, JSONObject().put("type", t)) }
        val params = JSONObject().put("type", "object").put("properties", p)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", desc).put("parameters", params)
        )
    }

    private fun toolDefs(): JSONArray = JSONArray()
        .put(tool("open_app", "Open an app on the phone. app is one of: camera, youtube, spotify", "app" to "string"))
        .put(tool("youtube_search", "Search YouTube and show the results", "query" to "string"))
        .put(tool("play_spotify", "Play a song, artist or playlist on Spotify", "query" to "string"))
        .put(tool("find_contacts", "Search the phone contacts by name. Returns matches with index, name and number. Always use this before calling or messaging someone", "name" to "string"))
        .put(tool("call_number", "Place a phone call to a number returned by find_contacts", "number" to "string", "name" to "string"))
        .put(tool("send_whatsapp", "Send a WhatsApp message to a number returned by find_contacts", "number" to "string", "name" to "string", "message" to "string"))
        .put(tool("lock_screen", "Lock the phone screen"))
        .put(tool("flashlight", "Turn the flashlight on or off", "on" to "boolean"))
        .put(tool("set_volume", "Change media volume. action is one of: up, down, mute, unmute, max, set. percent (0-100) only for set", "action" to "string", "percent" to "integer", required = listOf("action")))
        .put(tool("open_installed_app", "Open ANY app installed on the phone by its name (English name, e.g. WhatsApp, Instagram, Settings, Chrome, PhonePe)", "name" to "string"))
        .put(tool("get_time", "Get the current date and time"))

    private fun systemPrompt() =
        "You are $assistantName, a friendly voice assistant running on the user's Android phone. " +
        "Everything you write is spoken aloud, so answer in one or two short sentences with no markdown, lists or emojis. " +
        "You can control the phone with tools. To open any app use open_installed_app. The user may speak Telugu or English; reply in the same language (Telugu script for Telugu). To call or message someone, first use find_contacts. " +
        "If exactly one contact matches, go ahead. If several match, read out their names and ask which one; never guess. " +
        "If the text of a message is missing, ask for it. After an action succeeds, confirm briefly. " +
        "For questions, answer directly from your knowledge, and say so if you do not know. " +
        "Reply in the language the user speaks."

    // Groq: free, very fast, OpenAI-compatible API
    private val groqModel = "llama-3.3-70b-versatile"   // for even faster replies use "llama-3.1-8b-instant"

    private fun callClaude(messages: JSONArray): JSONObject {
        val all = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        for (i in 0 until messages.length()) all.put(messages.get(i))
        val body = JSONObject()
            .put("model", groqModel)
            .put("max_tokens", 300)
            .put("temperature", 0.3)
            .put("tools", toolDefs())
            .put("tool_choice", "auto")
            .put("messages", all)
        val conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("Authorization", "Bearer " + apiKey())
            setRequestProperty("User-Agent", "Riya/1.0")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private fun runAgent(userText: String) {
        busy = true
        busyStart = System.currentTimeMillis()
        listening = false
        muteBeep(false)
        recognizer?.cancel()
        Thread {
            var usedTool = false
            try {
                if (System.currentTimeMillis() - lastAgentTime > 180000 || history.length() > 24) history = JSONArray()
                lastAgentTime = System.currentTimeMillis()
                history.put(JSONObject().put("role", "user").put("content", userText))
                var reply = ""
                for (round in 0 until 6) {
                    var resp = callClaude(history)
                    if (resp.has("error") && resp.toString().contains("tool", ignoreCase = true)) resp = callClaude(history)
                    if (resp.has("error")) throw Exception(resp.getJSONObject("error").optString("message", "API error"))
                    val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    val text = if (msg.isNull("content")) "" else msg.optString("content", "")
                    val calls = msg.optJSONArray("tool_calls")
                    val assistant = JSONObject().put("role", "assistant").put("content", if (text.isBlank()) "" else text)
                    if (calls != null && calls.length() > 0) assistant.put("tool_calls", calls)
                    history.put(assistant)
                    reply = text.trim()
                    if (calls != null && calls.length() > 0) {
                        for (i in 0 until calls.length()) {
                            val c = calls.getJSONObject(i)
                            val fn = c.getJSONObject("function")
                            usedTool = true
                            val args = try { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                            val out = executeTool(fn.getString("name"), args)
                            history.put(JSONObject().put("role", "tool")
                                .put("tool_call_id", c.getString("id")).put("content", out))
                        }
                    } else break
                }
                handler.post {
                    busy = false
                    if (reply.isBlank()) startListening() else {
                        followUp = reply.trimEnd().let { it.endsWith("?") || it.endsWith("？") }  // only skip the name when Riya asked a question
                        say(reply)
                    }
                }
            } catch (e: Exception) {
                history = JSONArray()
                handler.post {
                    busy = false
                    toastOnce("AI unavailable: ${e.message}")
                    if (!usedTool) handle(teToEn(userText)) else startListening()
                }
            }
        }.start()
    }

    private fun executeTool(name: String, input: JSONObject): String = try {
        when (name) {
            "open_installed_app" -> openAppByName(input.optString("name"))
            "get_time" -> SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
            "lock_screen" -> {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) { dpm.lockNow(); "Screen locked" }
                else "Device admin is not enabled in the app"
            }
            "flashlight" -> setTorch(input.optBoolean("on", true))
            "set_volume" -> setVolumeSilent(input.optString("action"), if (input.has("percent")) input.optInt("percent") else null)
            "open_app" -> when (input.optString("app").lowercase()) {
                "camera" -> if (launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)) ||
                    launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) "Camera opened" else "Camera not found"
                "youtube" -> { youtubeSilent(null); "YouTube opened" }
                "spotify" -> {
                    val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
                    if (i != null && launch(i)) "Spotify opened" else "Spotify is not installed"
                }
                else -> "I can only open camera, youtube or spotify"
            }
            "youtube_search" -> { youtubeSilent(input.optString("query")); "YouTube search opened" }
            "play_spotify" -> {
                val song = input.optString("query")
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage("com.spotify.music")
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, song)
                }
                if (launch(i) || launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))))) "Spotify opened for $song"
                else "Spotify is not installed"
            }
            "find_contacts" -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) "Contacts permission is not granted"
                else {
                    val list = findContacts(input.optString("name")).take(8)
                    if (list.isEmpty()) "No contacts found"
                    else JSONArray().also { arr ->
                        list.forEachIndexed { i, c -> arr.put(JSONObject().put("index", i + 1).put("name", c.name).put("number", c.number)) }
                    }.toString()
                }
            }
            "call_number" -> if (dialNumber(input.optString("number"))) "Call started" else "Could not start the call"
            "send_whatsapp" -> {
                var digits = input.optString("number").filter { it.isDigit() }
                if (digits.length == 10) digits = defaultCountryCode + digits
                RiyaAccessibility.arm()
                val url = "https://wa.me/$digits?text=" + Uri.encode(input.optString("message"))
                if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "WhatsApp opened with the message" else "WhatsApp is not installed"
            }
            else -> "Unknown tool"
        }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    private fun youtubeSilent(query: String?) {
        val url = if (query.isNullOrBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        if (!launch(i)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun dialNumber(number: String): Boolean {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return true
            } catch (e: Exception) { /* fall through */ }
            if (launch(Intent(Intent.ACTION_CALL, uri))) return true
        }
        return launch(Intent(Intent.ACTION_DIAL, uri))
    }

    private fun setTorch(on: Boolean): String {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No flashlight found"
        cm.setTorchMode(id, on)
        torchOn = on
        return if (on) "Flashlight on" else "Flashlight off"
    }

    private fun setVolumeSilent(action: String, percent: Int?): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(st)
        when (action) {
            "up" -> repeat(2) { am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(2) { am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.setStreamVolume(st, 0, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.setStreamVolume(st, max / 2, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(st, max, AudioManager.FLAG_SHOW_UI)
            "set" -> am.setStreamVolume(st, ((percent ?: 50).coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            else -> return "Unknown volume action"
        }
        return "Volume is now " + (am.getStreamVolume(st) * 100 / max) + " percent"
    }

    // ---------- Other actions ----------

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun lockScreen() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            dpm.lockNow()
        } else say("Enable Device admin in the Riya app first")
    }

    private fun openCamera() {
        say("Opening camera")
        if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) {
            if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) say("Camera not found")
        }
    }

    private fun youtube(query: String?) {
        val url = if (query.isNullOrBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        say(if (query.isNullOrBlank()) "Opening YouTube" else "Searching $query on YouTube")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        if (!launch(i)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openSpotify() {
        say("Opening Spotify")
        val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i == null || !launch(i)) say("Spotify is not installed")
    }

    private fun playSpotify(song: String) {
        say("Playing $song")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))))) {
                say("Spotify is not installed")
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences("riya", Context.MODE_PRIVATE).edit().putBoolean("running", false).apply()
        running = false
        handler.removeCallbacksAndMessages(null)
        muteBeep(false)
        recognizer?.destroy()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
