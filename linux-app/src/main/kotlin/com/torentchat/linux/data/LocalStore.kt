package com.torentchat.linux.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalStore(private val dataDir: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private var convs = mutableListOf<Conversation>()
    private var msgs = mutableListOf<Message>()
    private var contacts = mutableListOf<Contact>()

    init { load() }

    private fun <T> readFile(p: Path, s: kotlinx.serialization.KSerializer<List<T>>): MutableList<T> =
        if (Files.exists(p)) try { json.decodeFromString(s, Files.readString(p)).toMutableList() } catch (_: Exception) { mutableListOf() } else mutableListOf()

    private fun load() {
        convs = readFile(dataDir.resolve("conversations.json"), ListSerializer(Conversation.serializer()))
        msgs = readFile(dataDir.resolve("messages.json"), ListSerializer(Message.serializer()))
        contacts = readFile(dataDir.resolve("contacts.json"), ListSerializer(Contact.serializer()))
    }

    private fun persist() {
        Files.createDirectories(dataDir)
        Files.writeString(dataDir.resolve("conversations.json"), json.encodeToString(ListSerializer(Conversation.serializer()), convs))
        Files.writeString(dataDir.resolve("messages.json"), json.encodeToString(ListSerializer(Message.serializer()), msgs))
        Files.writeString(dataDir.resolve("contacts.json"), json.encodeToString(ListSerializer(Contact.serializer()), contacts))
    }

    fun getConversations() = convs.sortedByDescending { it.lastMessageTimestamp ?: it.createdAt }
    fun getConversation(id: String) = convs.find { it.id == id }
    fun upsertConversation(c: Conversation) { val i = convs.indexOfFirst { it.id == c.id }; if (i >= 0) convs[i] = c else convs.add(c); persist() }
    fun createDirectConversation(local: String, remote: String, title: String): String {
        val id = "direct-${listOf(local, remote).sorted().joinToString("-")}"
        if (convs.none { it.id == id }) upsertConversation(Conversation(id, title, remote))
        return id
    }

    fun getMessages(cid: String) = msgs.filter { it.conversationId == cid }.sortedBy { it.timestamp }
    fun insertOutgoing(cid: String, sid: String, content: String): String {
        val m = Message(UUID.randomUUID().toString(), cid, sid, content, System.currentTimeMillis(), isOutgoing = true)
        msgs.add(m); updatePreview(cid, content, m.timestamp); persist(); return m.id
    }
    fun insertIncoming(cid: String, sid: String, content: String) {
        msgs.add(Message(UUID.randomUUID().toString(), cid, sid, content, System.currentTimeMillis(), "DELIVERED", false))
        updatePreview(cid, content, System.currentTimeMillis()); persist()
    }
    fun updateStatus(mid: String, s: String) { val i = msgs.indexOfFirst { it.id == mid }; if (i >= 0) { msgs[i] = msgs[i].copy(status = s); persist() } }
    private fun updatePreview(cid: String, p: String, ts: Long) { val i = convs.indexOfFirst { it.id == cid }; if (i >= 0) { convs[i].lastMessagePreview = p; convs[i].lastMessageTimestamp = ts } }

    fun addContact(pid: String, dn: String?, ik: String) { if (contacts.none { it.peerId == pid }) { contacts.add(Contact(pid, dn, ik)); persist() } }
}
