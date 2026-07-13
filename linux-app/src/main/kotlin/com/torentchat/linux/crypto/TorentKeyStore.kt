package com.torentchat.linux.crypto

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.concurrent.ConcurrentHashMap

class TorentKeyStore(
    identityKeyPair: IdentityKeyPair, val registrationId: Int,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, KyberPreKeyStore {
    private val _identityKeyPair = identityKeyPair
    private val trusted = ConcurrentHashMap<SignalProtocolAddress, IdentityKey>()
    private val preKeys = ConcurrentHashMap<Int, PreKeyRecord>()
    private val signedPreKeys = ConcurrentHashMap<Int, SignedPreKeyRecord>()
    private val sessions = ConcurrentHashMap<SignalProtocolAddress, SessionRecord>()

    override fun getIdentityKeyPair() = _identityKeyPair
    override fun getLocalRegistrationId() = registrationId
    override fun saveIdentity(a: SignalProtocolAddress, k: IdentityKey) =
        if (trusted[a] != null && trusted[a] != k) { trusted[a] = k; IdentityKeyStore.IdentityChange.REPLACED_EXISTING }
        else { trusted[a] = k; IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED }
    override fun isTrustedIdentity(a: SignalProtocolAddress, k: IdentityKey, d: IdentityKeyStore.Direction) = trusted[a] == null || trusted[a] == k
    override fun getIdentity(a: SignalProtocolAddress) = trusted[a]
    override fun loadPreKey(id: Int) = preKeys[id] ?: error("PreKey $id")
    override fun storePreKey(id: Int, r: PreKeyRecord) { preKeys[id] = r }
    override fun containsPreKey(id: Int) = preKeys.containsKey(id)
    override fun removePreKey(id: Int) { preKeys.remove(id) }
    override fun loadSignedPreKey(id: Int) = signedPreKeys[id] ?: error("SPK $id")
    override fun loadSignedPreKeys() = signedPreKeys.values.toMutableList()
    override fun storeSignedPreKey(id: Int, r: SignedPreKeyRecord) { signedPreKeys[id] = r }
    override fun containsSignedPreKey(id: Int) = signedPreKeys.containsKey(id)
    override fun removeSignedPreKey(id: Int) { signedPreKeys.remove(id) }
    override fun loadKyberPreKey(id: Int) = error("No Kyber")
    override fun loadKyberPreKeys() = mutableListOf<KyberPreKeyRecord>()
    override fun storeKyberPreKey(id: Int, r: KyberPreKeyRecord) {}
    override fun containsKyberPreKey(id: Int) = false
    override fun markKyberPreKeyUsed(id: Int, spkId: Int, b: ECPublicKey) {}
    override fun loadSession(a: SignalProtocolAddress) = sessions[a] ?: SessionRecord()
    override fun getSubDeviceSessions(n: String) = mutableListOf<Int>()
    override fun storeSession(a: SignalProtocolAddress, r: SessionRecord) { sessions[a] = r }
    override fun containsSession(a: SignalProtocolAddress) = sessions.containsKey(a)
    override fun deleteSession(a: SignalProtocolAddress) { sessions.remove(a) }
    override fun deleteAllSessions(n: String) { sessions.keys.filter { it.name == n }.forEach { sessions.remove(it) } }
    override fun loadExistingSessions(a: MutableList<SignalProtocolAddress>) =
        a.mapTo(mutableListOf()) { sessions[it] ?: throw NoSessionException("No session $it") }

    companion object {
        fun generate() = TorentKeyStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))

        fun generatePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
            (startId until startId + count).map { PreKeyRecord(it, ECKeyPair.generate()) }

        fun generateSignedPreKey(identityKeyPair: IdentityKeyPair, id: Int): SignedPreKeyRecord {
            val keyPair = ECKeyPair.generate()
            val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
            return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        }
    }
}
