package com.torentchat.di

import android.content.Context
import com.torentchat.BuildConfig
import com.torentchat.crypto.SignalSessionManager
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

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSignalingClient(): SignalingClient {
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
    fun provideSignalSessionManager(identityManager: IdentityManager): SignalSessionManager {
        val identity = identityManager.loadIdentity() ?: identityManager.createNewIdentity()
        return SignalSessionManager(
            keyStore = identity.keyStore,
            localPeerId = identity.peerId,
        )
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TorentDatabase {
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
