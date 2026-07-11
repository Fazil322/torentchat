package com.torentchat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.torentchat.data.local.dao.ContactDao
import com.torentchat.data.local.dao.ConversationDao
import com.torentchat.data.local.dao.MessageDao
import com.torentchat.data.local.entity.ContactEntity
import com.torentchat.data.local.entity.ConversationEntity
import com.torentchat.data.local.entity.MessageEntity

/**
 * Encrypted local database backed by SQLCipher.
 * ─────────────────────────────────────────────────────────────────────────────
 * All decrypted message content, contacts, and conversation metadata live here.
 * The database file is encrypted at rest with a key derived from:
 *   • Android Keystore-protected passphrase (Phase 5), OR
 *   • A device-specific key (development fallback).
 *
 * This ensures that even if the device is compromised or the app data is
 * extracted, message history remains unreadable without the key.
 *
 * The Room abstract class is standard; the encryption is applied at the
 * SQLite open helper level via SQLCipher's [SupportFactory] (wired up in the
 * DI module — see [com.torentchat.di.DatabaseModule]).
 */
@Database(
    entities = [
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TorentDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
