package com.torentchat.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.kem.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
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
 * Holds all cryptographic state required by libsignal:
 *   • Identity key pair (long-term Curve25519)
 *   • Registration ID
 *   • Pre-keys, signed pre-keys (for X3DH key agreement)
 *   • Kyber pre-keys (for PQXDH — post-quantum upgrade)
 *   • Sessions (Double Ratchet state per remote peer)
 *   • Trusted remote identity keys
 *
 * SECURITY NOTE: In Phase 5 this will be backed by an encrypted SQLCipher store
 * so keys never touch disk in plaintext.
 *
 * @param identityKeyPair  generated once at first launch by [IdentityManager]
 * @param registrationId   random 14-bit ID to avoid address collisions
 */
class TorentKeyStore(
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, KyberPreKeyStore, SenderKeyStore {

    private val trustedIdentities = ConcurrentHashMap<SignalProtocolAddress, IdentityKey>()
    private val preKeys = ConcurrentHashMap<Int, PreKeyRecord>()
    private val signedPreKeys = ConcurrentHashMap<Int, SignedPreKeyRecord>()
    private val kyberPreKeys = ConcurrentHashMap<Int, KyberPreKeyRecord>()
    private val sessions = ConcurrentHashMap<SignalProtocolAddress, SessionRecord>()
    private val senderKeys = ConcurrentHashMap<org.signal.libsignal.protocol.groups.SenderKeyName, SenderKeyRecord>()

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

    // ── KyberPreKeyStore (PQXDH — post-quantum) ───────────────────────────────

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        kyberPreKeys[kyberPreKeyId] ?: error("Kyber pre-key $kyberPreKeyId not found")

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> =
        kyberPreKeys.values.toMutableList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeys[kyberPreKeyId] = record
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        kyberPreKeys.containsKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: org.signal.libsignal.protocol.ecc.ECPublicKey,
    ) {
        // No-op: we keep Kyber pre-keys until explicitly removed (simpler for dev).
        // Production should remove one-time Kyber keys after use.
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
                throw org.signal.libsignal.protocol.NoSessionException("No session for $address")
            }
        }
        return result
    }

    // ── SenderKeyStore (for group messaging — no-op for now) ──────────────────

    override fun loadSenderKey(senderKeyName: org.signal.libsignal.protocol.groups.SenderKeyName): SenderKeyRecord? =
        senderKeys[senderKeyName]

    override fun storeSenderKey(
        senderKeyName: org.signal.libsignal.protocol.groups.SenderKeyName,
        record: SenderKeyRecord,
    ) {
        senderKeys[senderKeyName] = record
    }

    // ── Lightweight DTOs for extracting public material from records ───────────

    /** Public material extracted from a [SignedPreKeyRecord], safe to publish. */
    data class SignedPreKeyData(
        val id: Int,
        val publicKeyB64: String,
        val signatureB64: String,
    )

    /** Public material extracted from a [PreKeyRecord], safe to publish. */
    data class OneTimePreKeyData(
        val id: Int,
        val publicKeyB64: String,
    )

    // ── Key generation helpers ────────────────────────────────────────────────

    companion object {
        /** Generate a fresh identity key pair + registration ID for a new user. */
        fun generate(): TorentKeyStore {
            val identityKeyPair = IdentityKeyPair.generate()
            val registrationId = KeyHelper.generateRegistrationId(false)
            return TorentKeyStore(identityKeyPair, registrationId)
        }

        /** Generate a batch of one-time pre-keys (for X3DH). */
        fun generatePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
            (startId until startId + count).map { id ->
                PreKeyRecord(id, ECKeyPair.generate())
            }

        /** Generate a signed pre-key (rotated periodically). */
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
