package com.torentchat

// Thin Kotlin shim — loads Rust native library and calls JNI methods.
// All business logic (crypto, signaling, chat) is in Rust.
object TorentChatNative {
    init { System.loadLibrary("torentchat_android") }

    external fun getPeerId(): String
    external fun getPublicKey(): String
    external fun sendMessage(peerId: String, pubKey: String, content: String): Boolean
    external fun connect(peerId: String, pubKey: String)
    external fun pollMessages(): String
    external fun getConversations(): String
    external fun getMessages(peerId: String): String
}
