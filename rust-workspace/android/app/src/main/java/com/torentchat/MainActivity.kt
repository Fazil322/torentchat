package com.torentchat

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var selectedPeer = ""
    private var selectedKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val pidText = TextView(this).apply { text = "Peer ID: ${TorentChatNative.getPeerId()}" }
        val statusText = TextView(this).apply { text = "Ready" }

        val peerInput = EditText(this).apply { hint = "Peer ID" }
        val keyInput = EditText(this).apply { hint = "Public Key" }
        val connectBtn = Button(this).apply { text = "Connect" }
        val msgInput = EditText(this).apply { hint = "Message" }
        val sendBtn = Button(this).apply { text = "Send" }
        val msgsView = TextView(this).apply { text = "" }
        val pollBtn = Button(this).apply { text = "Poll Messages" }

        connectBtn.setOnClickListener {
            selectedPeer = peerInput.text.toString()
            selectedKey = keyInput.text.toString()
            TorentChatNative.connect(selectedPeer, selectedKey)
            statusText.text = "Connected to $selectedPeer"
        }

        sendBtn.setOnClickListener {
            val msg = msgInput.text.toString()
            if (msg.isNotEmpty() && selectedPeer.isNotEmpty()) {
                TorentChatNative.sendMessage(selectedPeer, selectedKey, msg)
                msgsView.text = "${msgsView.text}\n→ $msg"
                msgInput.text.clear()
            }
        }

        pollBtn.setOnClickListener {
            val json = TorentChatNative.pollMessages()
            val arr = JSONArray(json)
            var display = ""
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                display += "← ${item.getString("0")}: ${item.getString("1")}\n"
            }
            if (display.isNotEmpty()) msgsView.text = "${msgsView.text}\n$display"
            else statusText.text = "No new messages"
        }

        layout.addView(pidText)
        layout.addView(statusText)
        layout.addView(TextView(this).apply { text = "\nConnect:" })
        layout.addView(peerInput)
        layout.addView(keyInput)
        layout.addView(connectBtn)
        layout.addView(TextView(this).apply { text = "\nSend:" })
        layout.addView(msgInput)
        layout.addView(sendBtn)
        layout.addView(msgsView)
        layout.addView(pollBtn)

        setContentView(layout)

        // Auto-poll every 5 seconds
        handler.postDelayed(object : Runnable {
            override fun run() {
                val json = TorentChatNative.pollMessages()
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    msgsView.text = "${msgsView.text}\n← ${item.getString("0")}: ${item.getString("1")}"
                }
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }
}
