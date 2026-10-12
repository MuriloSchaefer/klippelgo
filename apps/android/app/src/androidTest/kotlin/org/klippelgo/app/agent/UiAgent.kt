package org.klippelgo.app.agent

import android.app.Instrumentation
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The hands and eyes of the test harness on the phone (`tests/README.md`). It is not a test: it is started as
 * one because that is how Android lends a process the right to read the screen and inject touches, and it then
 * serves requests until told to stop. The harness reaches it through `adb forward`.
 *
 * It does what a person can do and nothing else: look at what is on screen, tap, swipe, type into a field,
 * press back. It never calls into the app, so what a test sees is what a user would.
 *
 * One request per line, as JSON, and one line back:
 *
 *   {"op":"dump"}                                   the screen as UI Automator's XML, in `xml`
 *   {"op":"tap","x":540,"y":1200}
 *   {"op":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"steps":20}     one step is 5 ms
 *   {"op":"type","tag":"create-account.name","text":"Ana"}        replaces the field's text
 *   {"op":"back"}
 *   {"op":"stop"}
 *
 * The socket is a local one, named and not on the network, and only adb's shell user (or root) is answered:
 * another app on the device that connects is hung up on. Even so, the test APK belongs on test emulators and
 * never on a phone someone uses.
 */
@RunWith(AndroidJUnit4::class)
class UiAgent {
    @Test
    fun serve() {
        // The map and the progress rings never stop drawing, so waiting for the screen to settle would wait forever.
        Configurator.getInstance().setWaitForIdleTimeout(0).setActionAcknowledgmentTimeout(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val server = LocalServerSocket(SOCKET)
        try {
            var running = true
            while (running) {
                server.accept().use { socket ->
                    if (socket.peerCredentials.uid in TRUSTED_UIDS) running = talk(instrumentation, device, socket)
                }
            }
        } finally {
            server.close()
        }
    }

    /** Answers one connection's requests. False once it was told to stop. */
    private fun talk(instrumentation: Instrumentation, device: UiDevice, socket: LocalSocket): Boolean {
        val input = socket.inputStream.bufferedReader()
        val output = socket.outputStream
        while (true) {
            val line = input.readLine() ?: return true
            val answer = JSONObject()
            var stop = false
            try {
                val request = JSONObject(line)
                when (val op = request.getString("op")) {
                    "dump" -> {
                        // Android keeps a copy of the screen's tree that can lag what is drawn. Read it afresh.
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        answer.put("xml", ByteArrayOutputStream().also(device::dumpWindowHierarchy).toString("UTF-8"))
                    }
                    "tap" -> device.click(request.getInt("x"), request.getInt("y"))
                    "swipe" -> device.swipe(
                        request.getInt("x1"), request.getInt("y1"), request.getInt("x2"), request.getInt("y2"), request.optInt("steps", 20),
                    )
                    "type" -> {
                        val field = device.findObject(By.res(request.getString("tag"))) ?: error("no field tagged ${request.getString("tag")} on screen")
                        field.text = request.getString("text")
                    }
                    "back" -> device.pressBack()
                    "stop" -> stop = true
                    else -> error("unknown op $op")
                }
                answer.put("ok", true)
            } catch (e: Throwable) {
                answer.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            output.write((answer.toString() + "\n").toByteArray())
            output.flush()
            if (stop) return false
        }
    }

    private companion object {
        const val SOCKET = "klippelgo-ui-agent"

        /** Root and adb's shell, which is who `adb forward` connects as. */
        val TRUSTED_UIDS = setOf(0, 2000)
    }
}
