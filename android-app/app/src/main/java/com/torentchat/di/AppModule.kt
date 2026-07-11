package com.torentchat.di

import android.content.Context
import com.torentchat.BuildConfig
import com.torentchat.crypto.SignalSessionManager
import com.torentchat.crypto.TorentKeyStore
import com.torentchat.data.local.TorentDatabase
import com.torentchat.data.local.dao.ContactDao
import com.torentchat.data.local.dao.ConversationDao
import com.torentchat.data.local.dao.MessageDao
import com.torentchat.identity.IdentityManager
import com.torentchat.signaling.SignalingClient
import com.torentchat.webrtc.WebRtcManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency-injection module — wires up all singletons.
 * ─────────────────────────────────────────────────────────────────────────────
 * Bound singletons:
 *   • [SignalingClient]  — HTTP client for the Cloudflare Worker relay
 *   • [WebRtcManager]    — WebRTC PeerConnectionFactory
 *   • [TorentDatabase]   — encrypted Room/SQLCipher database
 *   • [IdentityManager]  — anonymous identity management
 *   • [SignalSessionManager] — E2EE engine
 *
 * NOTE: The relay URL is currently hardcoded for development. In production,
 * move this to BuildConfig or a remote config so it can be rotated.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSignalingClient(): SignalingClient {
        // Relay URL comes from BuildConfig — set via SIGNALING_RELAY_URL env var
        // at build time (CI injects from GitHub Secrets). See docs/CI_CD_SETUP.md.
        val relayUrl = BuildConfig.SIGNALING_RELAY_URL
        return SignalingClient(relayUrl)
    }

    @Provides
    @Singleton
    fun provideWebRtcManager(@ApplicationContext context: Context): WebRtcManager {
        return WebRtcManager().apply { init(context) }
    }

    @Provides
    @Singleton
    fun provideIdentityManager(): IdentityManager = IdentityManager()

    @Provides
    @Singleton
    fun provideSignalSessionManager(identityManager: IdentityManager): SignalSessionManager {
        // Ensure identity exists (create on first call).
        val identity = identityManager.currentIdentity
            ?: identityManager.createNewIdentity()
        return SignalSessionManager(
            keyStore = identity.keyStore,
            localPeerId = identity.peerId,
        )
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TorentDatabase {
        // TODO(Phase 5): replace with SQLCipher-encrypted SupportFactory.
        //   For now, standard Room (data is still app-private via sandbox).
        return androidx.room.Room.databaseBuilder(
            context,
            TorentDatabase::class.java,
            "torentchat.db",
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideContactDao(db: TorentDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideConversationDao(db: TorentDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: TorentDatabase): MessageDao = db.messageDao()
}
