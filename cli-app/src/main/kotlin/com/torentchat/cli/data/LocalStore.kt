package com.torentchat.cli.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Serializable data class Conversation(val id: String, val title: String, val peerIds: String, var lastPreview: String? = null, var lastTs: Long? = null)
@Serializable data class Message(val id: String, val cid: String, val sender: String, val content: String, val ts: Long, var status: String = "SENDING", val out: Boolean)
@Serializable data class Contact(val peerId: String, val name: String?, val identityKey: String?)

class LocalStore(private val dir: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private var convs = load("conversations.json", Conversation.serializer()) { mutableListOf() }
    private var msgs = load("messages.json", Message.serializer()) { mutableListOf() }
    private var contacts = load("contacts.json", Contact.serializer()) { mutableListOf() }

    private inline fun <reified T : Any> load(name: String, ser: kotlinx.serialization.KSerializer<T>, default: () -> MutableList<T>): MutableList<T> {
        val f = dir.resolve(name)
        if (!Files.exists(f)) return default()
        return try { json.decodeFromString(ListSerializer(ser), Files.readString(f)).toMutableList() } catch (_: Exception) { default() }
    }

    private fun save() {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("conversations.json"), json.encodeToString(ListSerializer(Conversation.serializer()), convs))
        Files.writeString(dir.resolve("messages.json"), json.encodeToString(ListSerializer(Message.serializer()), msgs))
        Files.writeString(dir.resolve("contacts.json"), json.encodeToString(ListSerializer(Contact.serializer()), contacts))
    }

    fun listConversations() = convs.sortedByDescending { it.lastTs ?: 0L }
    fun getConversation(id: String) = convs.find { it.id == id }
    fun createDirectConv(local: String, remote: String): String {
        val id = "direct-${listOf(local, remote).sorted().joinToString("-")}"
        if (convs.none { it.id == id }) { convs.add(Conversation(id, remote, remote)); save() }
        return id
    }
    fun getMessages(cid: String) = msgs.filter { it.cid == cid }.sortedBy { it.ts }
    fun insertOutgoing(cid: String, sender: String, content: String): String {
        val m = Message(UUID.randomUUID().toString(), cid, sender, content, System.currentTimeMillis(), "SENT", true)
        msgs.add(m); convs.find { it.id == cid }?.let { it.lastPreview = content; it.lastTs = m.ts }; save(); return m.id
    }
    fun insertIncoming(cid: String, sender: String, content: String) {
        msgs.add(Message(UUID.randomUUID().toString(), cid, sender, content, System.currentTimeMillis(), "DELIVERED", false))
        convs.find { it.id == cid }?.let { it.lastPreview = content; it.lastTs = System.currentTimeMillis() }; save()
    }
    fun updateStatus(mid: String, s: String) { val i = msgs.indexOfFirst { it.id == mid }; if (i >= 0) { msgs[i].status = s; save() } }
    fun addContact(pid: String, name: String?, ik: String?) { if (contacts.none { it.peerId == pid }) { contacts.add(Contact(pid, name, ik)); save() } }
}
