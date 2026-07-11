package com.torentchat.crypto

import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.state.IdentityKeyStore
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.PreKeyStore
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SessionStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyStore
import org.whispersystems.libsignal.util.KeyHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory Signal Protocol store.
 * ─────────────────────────────────────────────────────────────────────────────
 * Holds all cryptographic state required by libsignal:
 *   • Identity key pair (long-term Curve25519)
 *   • Registration ID
 *   • Pre-keys, signed pre-keys (for X3DH key agreement)
 *   • Sessions (Double Ratchet state per remote peer)
 *   • Trusted remote identity keys
 *
 * SECURITY NOTE: In Phase 5 this will be backed by an encrypted SQLCipher store
 * (via [com.torentchat.data.local.EncryptedDatabase]) so keys never touch disk
 * in plaintext. For now this in-memory impl is used during development.
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

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey) {
        trustedIdentities[address] = identityKey
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        // TOFU (Trust On First Use): a new identity is trusted on first encounter.
        // Subsequent encounters must match, otherwise it's a MITM/key-change.
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

    override fun getSubDevicesSessions(name: String): MutableList<Int> = mutableListOf()

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

    // ── Key generation helpers ────────────────────────────────────────────────

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

    companion object {
        /** Generate a fresh identity key pair + registration ID for a new user. */
        fun generate(): TorentKeyStore {
            val identityKeyPair = Curve.generateKeyPair()
            val registrationId = KeyHelper.generateRegistrationId(false)
            return TorentKeyStore(
                identityKeyPair = IdentityKeyPair(IdentityKey(identityKeyPair.publicKey), identityKeyPair.privateKey),
                registrationId = registrationId,
            )
        }

        /** Generate a batch of one-time pre-keys (for X3DH). */
        fun generatePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
            (startId until startId + count).map { id ->
                PreKeyRecord(id, Curve.generateKeyPair())
            }

        /** Generate a signed pre-key (rotated periodically). */
        fun generateSignedPreKey(
            identityKeyPair: IdentityKeyPair,
            id: Int,
        ): SignedPreKeyRecord {
            val keyPair = Curve.generateKeyPair()
            val signature = Curve.calculateSignature(
                identityKeyPair.privateKey,
                keyPair.publicKey.serialize(),
            )
            return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        }
    }
}
