package com.torentchat.desktop.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalStore(private val dataDir: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val convFile = dataDir.resolve("conversations.json")
    private val msgFile = dataDir.resolve("messages.json")
    private val contactFile = dataDir.resolve("contacts.json")

    private var conversations = mutableListOf<Conversation>()
    private var messages = mutableListOf<Message>()
    private var contacts = mutableListOf<Contact>()

    init { load() }

    private fun load() {
        conversations = readFile(convFile, ListSerializer(Conversation.serializer()))
        messages = readFile(msgFile, ListSerializer(Message.serializer()))
        contacts = readFile(contactFile, ListSerializer(Contact.serializer()))
    }

    private fun <T> readFile(path: Path, serializer: kotlinx.serialization.KSerializer<List<T>>): MutableList<T> {
        if (!Files.exists(path)) return mutableListOf()
        return try { json.decodeFromString(serializer, Files.readString(path)).toMutableList() }
        catch (_: Exception) { mutableListOf() }
    }

    private fun persist() {
        Files.createDirectories(dataDir)
        Files.writeString(convFile, json.encodeToString(ListSerializer(Conversation.serializer()), conversations))
        Files.writeString(msgFile, json.encodeToString(ListSerializer(Message.serializer()), messages))
        Files.writeString(contactFile, json.encodeToString(ListSerializer(Contact.serializer()), contacts))
    }

    // ── Conversations ──────────────────────────────────────────────────────────
    fun getConversations(): List<Conversation> = conversations.sortedByDescending { it.lastMessageTimestamp ?: it.createdAt }
    fun getConversation(id: String): Conversation? = conversations.find { it.id == id }
    fun upsertConversation(conv: Conversation) {
        val idx = conversations.indexOfFirst { it.id == conv.id }
        if (idx >= 0) conversations[idx] = conv else conversations.add(conv)
        persist()
    }
    fun createDirectConversation(localPeerId: String, remotePeerId: String, title: String): String {
        val id = "direct-${listOf(localPeerId, remotePeerId).sorted().joinToString("-")}"
        if (conversations.none { it.id == id }) {
            upsertConversation(Conversation(id = id, title = title, peerIds = remotePeerId))
        }
        return id
    }

    // ── Messages ───────────────────────────────────────────────────────────────
    fun getMessages(conversationId: String): List<Message> = messages.filter { it.conversationId == conversationId }.sortedBy { it.timestamp }
    fun insertOutgoing(conversationId: String, senderId: String, content: String): String {
        val msg = Message(UUID.randomUUID().toString(), conversationId, senderId, content, timestamp = System.currentTimeMillis(), isOutgoing = true)
        messages.add(msg)
        updateConvPreview(conversationId, content, msg.timestamp)
        persist()
        return msg.id
    }
    fun insertIncoming(conversationId: String, senderId: String, content: String) {
        val msg = Message(UUID.randomUUID().toString(), conversationId, senderId, content, timestamp = System.currentTimeMillis(), status = "DELIVERED", isOutgoing = false)
        messages.add(msg)
        updateConvPreview(conversationId, content, msg.timestamp)
        persist()
    }
    fun updateStatus(messageId: String, status: String) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) { messages[idx] = messages[idx].copy(status = status); persist() }
    }
    private fun updateConvPreview(conversationId: String, preview: String, ts: Long) {
        val idx = conversations.indexOfFirst { it.id == conversationId }
        if (idx >= 0) { conversations[idx].lastMessagePreview = preview; conversations[idx].lastMessageTimestamp = ts }
    }

    // ── Contacts ───────────────────────────────────────────────────────────────
    fun getContacts(): List<Contact> = contacts
    fun addContact(peerId: String, displayName: String?, identityKey: String?) {
        if (contacts.none { it.peerId == peerId }) {
            contacts.add(Contact(peerId, displayName, identityKey)); persist()
        }
    }
}
