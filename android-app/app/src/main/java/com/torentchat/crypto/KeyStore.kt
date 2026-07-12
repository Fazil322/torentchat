package com.torentchat.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory Signal Protocol store (libsignal v0.86.x API).
 * ─────────────────────────────────────────────────────────────────────────────
 * Implements the four core stores: IdentityKey, PreKey, SignedPreKey, Session.
 *
 * NOTE: KyberPreKeyStore and SenderKeyStore are NOT implemented yet.
 * SessionCipher in libsignal 0.86.x requires KyberPreKeyStore — when we wire
 * up full E2EE in Phase 2, we'll either:
 *   1. Use a SignalProtocolStore wrapper that provides no-op Kyber/SenderKey
 *      implementations, OR
 *   2. Construct SessionCipher with a custom store bundle.
 *
 * For now, identity/key management + session state works for key generation
 * and pre-key bundle publishing.
 *
 * @param identityKeyPair  generated once at first launch by [IdentityManager]
 * @param registrationId   random 14-bit ID to avoid address collisions
 */
class TorentKeyStore(
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore {

    private val trustedIdentities = ConcurrentHashMap<SignalProtocolAddress, IdentityKey>()
    private val preKeys = ConcurrentHashMap<Int, PreKeyRecord>()
    private val signedPreKeys = ConcurrentHashMap<Int, SignedPreKeyRecord>()
    private val sessions = ConcurrentHashMap<SignalProtocolAddress, SessionRecord>()

    // ── IdentityKeyStore ──────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = trustedIdentities[address]
        trustedIdentities[address] = identityKey
        return if (existing != null && existing != identityKey) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        // TOFU (Trust On First Use): a new identity is trusted on first encounter.
        val existing = trustedIdentities[address]
        return existing == null || existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        trustedIdentities[address]

    // ── PreKeyStore ───────────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys[preKeyId] ?: error("Pre-key $preKeyId not found")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeys[preKeyId] = record
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)

    override fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId)
    }

    /** @return all remaining one-time pre-keys, for publishing to the signaling relay. */
    fun allPreKeys(): Map<Int, PreKeyRecord> = preKeys.toMap()

    // ── SignedPreKeyStore ─────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys[signedPreKeyId] ?: error("Signed pre-key $signedPreKeyId not found")

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> =
        signedPreKeys.values.toMutableList()

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeys[signedPreKeyId] = record
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        signedPreKeys.containsKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId)
    }

    // ── SessionStore ──────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        sessions[address] ?: SessionRecord()

    override fun getSubDeviceSessions(name: String): MutableList<Int> = mutableListOf()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessions[address] = record
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        sessions.containsKey(address)

    override fun deleteSession(address: SignalProtocolAddress) {
        sessions.remove(address)
    }

    override fun deleteAllSessions(name: String) {
        sessions.keys.filter { it.name == name }.forEach { sessions.remove(it) }
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        val result = mutableListOf<SessionRecord>()
        for (address in addresses) {
            val session = sessions[address]
            if (session != null) {
                result.add(session)
            } else {
                throw NoSessionException("No session for $address")
            }
        }
        return result
    }

    // ── Lightweight DTOs for extracting public material from records ───────────

    data class SignedPreKeyData(
        val id: Int,
        val publicKeyB64: String,
        val signatureB64: String,
    )

    data class OneTimePreKeyData(
        val id: Int,
        val publicKeyB64: String,
    )

    // ── Key generation helpers ────────────────────────────────────────────────

    companion object {
        fun generate(): TorentKeyStore {
            val identityKeyPair = IdentityKeyPair.generate()
            val registrationId = KeyHelper.generateRegistrationId(false)
            return TorentKeyStore(identityKeyPair, registrationId)
        }

        fun generatePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
            (startId until startId + count).map { id ->
                PreKeyRecord(id, ECKeyPair.generate())
            }

        fun generateSignedPreKey(
            identityKeyPair: IdentityKeyPair,
            id: Int,
        ): SignedPreKeyRecord {
            val keyPair = ECKeyPair.generate()
            val signature = identityKeyPair.privateKey
                .calculateSignature(keyPair.publicKey.serialize())
            return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        }
    }
}
