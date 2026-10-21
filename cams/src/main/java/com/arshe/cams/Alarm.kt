package com.arshe.cams

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * NVR analytics over ONVIF events (PullPoint). Ajax reports its detections as
 * tns1:RuleEngine/tnsajax:ObjectDetector/Detection with <ajax:Object class="person" confidence="91" …/>
 * per video source; [onPerson] fires for every such sighting.
 */
class Events(private val nvr: Nvr, private val onPerson: (source: String, confidence: Int) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::loop, "onvif-events").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    private fun loop() {
        val service = "http://${nvr.host}:${nvr.onvifPort}/onvif/event_service"
        while (running) {
            try {
                val sub = Onvif.soap(nvr, service,
                    "<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT600S</tev:InitialTerminationTime></tev:CreatePullPointSubscription>")
                val addr = Regex("<(?:\\w+:)?Address>([^<]+)<").find(sub)?.groupValues?.get(1) ?: error("no pull point")
                val until = System.currentTimeMillis() + 540_000 // a fresh subscription before this one expires
                while (running && System.currentTimeMillis() < until) {
                    val r = Onvif.soap(nvr, addr,
                        "<tev:PullMessages><tev:Timeout>PT10S</tev:Timeout><tev:MessageLimit>100</tev:MessageLimit></tev:PullMessages>",
                        header = "<a:To s:mustUnderstand=\"1\">$addr</a:To>", timeout = 20_000)
                    parse(r)
                }
            } catch (e: Exception) {
                Log.w("Cams", "events: $e")
                Thread.sleep(5000)
            }
        }
    }

    private fun parse(xml: String) {
        for (m in MESSAGE.findAll(xml)) {
            val msg = m.value
            if (!msg.contains("ObjectDetector")) continue
            val source = SOURCE.find(msg)?.groupValues?.get(1) ?: continue
            for (o in OBJECT.findAll(msg)) {
                val attrs = ATTR.findAll(o.value).associate { it.groupValues[1] to it.groupValues[2] }
                if (attrs["class"] == "person") onPerson(source, attrs["confidence"]?.toIntOrNull() ?: 0)
            }
        }
    }

    private companion object {
        val MESSAGE = Regex("<(?:\\w+:)?NotificationMessage>.*?</(?:\\w+:)?NotificationMessage>", RegexOption.DOT_MATCHES_ALL)
        val SOURCE = Regex("Name=\"VideoSourceToken\" Value=\"([^\"]+)\"")
        val OBJECT = Regex("<(?:\\w+:)?Object\\b[^>]*>")
        val ATTR = Regex("(\\w+)=\"([^\"]*)\"")
    }
}

/** Russian announcements in a female voice from the on-device TTS (works offline once the voice is installed). */
class Announcer(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) setup()
    }

    private fun setup() {
        val ru = Locale("ru", "RU")
        tts.language = ru
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { it.locale.language == "ru" }
        voices.forEach { Log.i("Cams", "tts voice ${it.name} net=${it.isNetworkConnectionRequired} q=${it.quality}") }
        pick(voices)?.let { tts.voice = it }
        tts.setSpeechRate(0.95f)
        ready = true
        Log.i("Cams", "tts ready: ${tts.voice?.name}")
    }

    // Google's Russian voices: ruf/rud/rue are female, ruc/ruw male; prefer offline ones.
    private fun pick(voices: List<Voice>): Voice? {
        val offline = voices.filter { !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") }
        for (id in listOf("ruf", "rud", "rue")) {
            (offline.firstOrNull { it.name.contains("-x-$id") } ?: voices.firstOrNull { it.name.contains("-x-$id") })?.let { return it }
        }
        return offline.firstOrNull()
    }

    fun say(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), text)
    }

    fun shutdown() = tts.shutdown()
}
