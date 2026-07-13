package com.torentchat.cli

import com.torentchat.cli.chat.ChatService
import com.torentchat.cli.data.LocalStore
import com.torentchat.cli.identity.IdentityManager
import com.torentchat.cli.signaling.SignalingClient
import kotlinx.coroutines.*
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

// ANSI color codes
const val RESET = "\u001B[0m"
const val BOLD = "\u001B[1m"
const val CYAN = "\u001B[36m"
const val GREEN = "\u001B[32m"
const val YELLOW = "\u001B[33m"
const val RED = "\u001B[31m"
const val DIM = "\u001B[2m"
const val MAGENTA = "\u001B[35m"

const val RELAY_URL = "https://torentchat-worker.ztik-user.workers.dev"
val DATA_DIR = Paths.get(System.getProperty("user.home"), ".torentchat")

fun main() = runBlocking {
    val idMgr = IdentityManager(DATA_DIR)
    val store = LocalStore(DATA_DIR)
    val signaling = SignalingClient(RELAY_URL)
    val chat = ChatService(idMgr, store, signaling)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    chat.initialize(scope)

    // Listen for incoming messages in background
    val incomingJob = scope.launch {
        chat.incoming.collect { (sender, content) ->
            print("\r${GREEN}← [$sender] $content$RESET\n> ")
            System.out.flush()
        }
    }

    printBanner()
    println("${DIM}Peer ID: ${BOLD}${chat.peerId}$RESET")
    println("${DIM}Relay: $RELAY_URL$RESET")
    println()

    val scanner = java.util.Scanner(System.`in`)
    var currentConvId: String? = null

    while (true) {
        if (currentConvId != null) {
            val conv = chat.getConversation(currentConvId!!)
            print("${CYAN}[${conv?.title ?: "chat"}]${RESET} > ")
        } else {
            print("${MAGENTA}torentchat${RESET} > ")
        }
        System.out.flush()

        val line = scanner.nextLine()?.trim() ?: break
        if (line.isEmpty()) continue

        val parts = line.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1) ?: ""

        when (cmd) {
            "/help", "help", "?" -> printHelp()
            "/id", "/whoami" -> println("${BOLD}Peer ID:$RESET ${chat.peerId}")
            "/list", "/ls" -> {
                val convs = chat.listConversations()
                if (convs.isEmpty()) println("${DIM}Belum ada percakapan. Gunakan /connect <peerId>$RESET")
                else convs.forEachIndexed { i, c -> println("  ${BOLD}${i + 1}.$RESET ${c.title} ${DIM}${c.lastPreview ?: "(no messages)"}$RESET") }
            }
            "/connect" -> {
                if (arg.isBlank()) println("${RED}Usage: /connect <peerId>$RESET")
                else {
                    chat.createConversationWithPeer(arg)
                    println("${GREEN}✓ Terhubung dengan $arg$RESET")
                    val conv = chat.listConversations().find { it.peerIds == arg }
                    currentConvId = conv?.id
                    println("${DIM}Sekarang dalam percakapan dengan $arg. Ketik pesan untuk mengirim. /back untuk kembali.$RESET")
                }
            }
            "/open" -> {
                if (arg.isBlank()) println("${RED}Usage: /open <conversationId>$RESET")
                else {
                    val conv = chat.listConversations().getOrNull(arg.toIntOrNull()?.minus(1) ?: -1)
                    if (conv != null) {
                        currentConvId = conv.id
                        println("${DIM}Opened: ${conv.title}$RESET")
                        chat.getMessages(conv.id).forEach { printMessage(it, chat.peerId) }
                    } else println("${RED}Percakapan tidak ditemukan$RESET")
                }
            }
            "/back", "/close" -> {
                currentConvId = null
                println("${DIM}Kembali ke menu utama$RESET")
            }
            "/quit", "/exit", "/bye" -> {
                println("${YELLOW}Sampai jumpa! 🔐$RESET")
                break
            }
            "/status" -> {
                println("${BOLD}Status:$RESET")
                println("  Peer ID: ${chat.peerId}")
                println("  Conversations: ${chat.listConversations().size}")
                println("  Current chat: ${currentConvId ?: "(none)"}")
                println("  Data dir: $DATA_DIR")
            }
            else -> {
                // If in a conversation, send as message
                if (currentConvId != null) {
                    val conv = chat.getConversation(currentConvId!!)
                    val rid = conv?.peerIds ?: ""
                    if (rid.isNotEmpty()) {
                        print("${DIM}→ sending...$RESET\r")
                        runBlocking { chat.sendMessage(rid, line) }
                        println("${CYAN}→ [${SimpleDateFormat("HH:mm").format(Date())}] $line$RESET")
                    }
                } else {
                    println("${RED}Unknown command: $cmd${RESET}. Ketik ${BOLD}/help$RESET${RED} untuk bantuan.$RESET")
                }
            }
        }
    }

    incomingJob.cancel()
    chat.shutdown()
}

fun printBanner() {
    println()
    println("${CYAN}${BOLD}╔══════════════════════════════════════════╗${RESET}")
    println("${CYAN}${BOLD}║         🔐  TorentChat CLI  🔐            ║${RESET}")
    println("${CYAN}${BOLD}║   P2P Encrypted Chat — Signal Protocol   ║${RESET}")
    println("${CYAN}${BOLD}╚══════════════════════════════════════════╝${RESET}")
    println()
}

fun printHelp() {
    println()
    println("${BOLD}Commands:$RESET")
    println("  ${CYAN}/help$RESET              Show this help")
    println("  ${CYAN}/id$RESET                Show your Peer ID")
    println("  ${CYAN}/list$RESET              List all conversations")
    println("  ${CYAN}/connect <peerId>$RESET Connect to a peer by ID")
    println("  ${CYAN}/open <num>$RESET        Open conversation by list number")
    println("  ${CYAN}/back$RESET              Leave current conversation")
    println("  ${CYAN}/status$RESET            Show connection status")
    println("  ${CYAN}/quit$RESET              Exit TorentChat")
    println()
    println("${DIM}In a conversation, type any text to send (encrypted).$RESET")
    println("${DIM}Incoming messages appear automatically with ← prefix.$RESET")
    println()
}

fun printMessage(m: com.torentchat.cli.data.Message, myId: String) {
    val time = SimpleDateFormat("HH:mm").format(Date(m.ts))
    if (m.out) println("  ${CYAN}→ [$time] $m.content$RESET")
    else println("  ${GREEN}← [$time] $m.content$RESET")
}
