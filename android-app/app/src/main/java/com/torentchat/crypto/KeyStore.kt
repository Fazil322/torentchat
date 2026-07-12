package com.torentchat.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
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
 * In-memory Signal Protocol store implementing all required interfaces for
 * libsignal v0.86.x: IdentityKeyStore, PreKeyStore, SignedPreKeyStore,
 * SessionStore, KyberPreKeyStore, and SenderKeyStore.
 *
 * KyberPreKeyStore and SenderKeyStore are implemented as no-ops (we don't use
 * PQXDH one-time Kyber keys or group sender keys yet), but they must be present
 * because SessionCipher requires them.
 */
class TorentKeyStore(
    identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, KyberPreKeyStore, SenderKeyStore {

    private val _identityKeyPair: IdentityKeyPair = identityKeyPair
    private val trustedIdentities = ConcurrentHashMap<SignalProtocolAddress, IdentityKey>()
    private val preKeys = ConcurrentHashMap<Int, PreKeyRecord>()
    private val signedPreKeys = ConcurrentHashMap<Int, SignedPreKeyRecord>()
    private val sessions = ConcurrentHashMap<SignalProtocolAddress, SessionRecord>()

    // ── IdentityKeyStore ──────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair = _identityKeyPair

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
        val existing = trustedIdentities[address]
        return existing == null || existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = trustedIdentities[address]

    // ── PreKeyStore ───────────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        preKeys[preKeyId] ?: error("Pre-key $preKeyId not found")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { preKeys[preKeyId] = record }
    override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)
    override fun removePreKey(preKeyId: Int) { preKeys.remove(preKeyId) }

    fun allPreKeys(): Map<Int, PreKeyRecord> = preKeys.toMap()

    // ── SignedPreKeyStore ─────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        signedPreKeys[signedPreKeyId] ?: error("Signed pre-key $signedPreKeyId not found")

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = signedPreKeys.values.toMutableList()
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) { signedPreKeys[signedPreKeyId] = record }
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeys.containsKey(signedPreKeyId)
    override fun removeSignedPreKey(signedPreKeyId: Int) { signedPreKeys.remove(signedPreKeyId) }

    // ── KyberPreKeyStore (no-op — PQXDH not used yet) ─────────────────────────

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        error("No Kyber pre-keys stored (PQXDH not enabled)")

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = mutableListOf()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        // No-op: we don't generate Kyber pre-keys. SessionCipher won't call this
        // for standard X3DH sessions without PQXDH.
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = false

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        // No-op
    }

    // ── SessionStore ──────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        sessions[address] ?: SessionRecord()

    override fun getSubDeviceSessions(name: String): MutableList<Int> = mutableListOf()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessions[address] = record
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = sessions.containsKey(address)

    override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address) }

    override fun deleteAllSessions(name: String) {
        sessions.keys.filter { it.name == name }.forEach { sessions.remove(it) }
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        val result = mutableListOf<SessionRecord>()
        for (address in addresses) {
            val session = sessions[address] ?: throw NoSessionException("No session for $address")
            result.add(session)
        }
        return result
    }

    // ── SenderKeyStore (no-op — group messaging not used yet) ─────────────────

    override fun loadSenderKey(sender: SignalProtocolAddress): SenderKeyRecord? = null

    override fun storeSenderKey(sender: SignalProtocolAddress, record: SenderKeyRecord) {
        // No-op
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    data class SignedPreKeyData(val id: Int, val publicKeyB64: String, val signatureB64: String)
    data class OneTimePreKeyData(val id: Int, val publicKeyB64: String)

    // ── Key generation ────────────────────────────────────────────────────────

    companion object {
        fun generate(): TorentKeyStore {
            val identityKeyPair = IdentityKeyPair.generate()
            val registrationId = KeyHelper.generateRegistrationId(false)
            return TorentKeyStore(identityKeyPair, registrationId)
        }

        fun generatePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
            (startId until startId + count).map { PreKeyRecord(it, ECKeyPair.generate()) }

        fun generateSignedPreKey(identityKeyPair: IdentityKeyPair, id: Int): SignedPreKeyRecord {
            val keyPair = ECKeyPair.generate()
            val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
            return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        }
    }
}
