package com.torentchat.desktop.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
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

class TorentKeyStore(
    identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, KyberPreKeyStore {

    private val _identityKeyPair = identityKeyPair
    private val trustedIdentities = ConcurrentHashMap<SignalProtocolAddress, IdentityKey>()
    private val preKeys = ConcurrentHashMap<Int, PreKeyRecord>()
    private val signedPreKeys = ConcurrentHashMap<Int, SignedPreKeyRecord>()
    private val sessions = ConcurrentHashMap<SignalProtocolAddress, SessionRecord>()
    private val kyberPreKeys = ConcurrentHashMap<Int, org.signal.libsignal.protocol.state.KyberPreKeyRecord>()

    override fun getIdentityKeyPair() = _identityKeyPair
    override fun getLocalRegistrationId() = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = trustedIdentities[address]
        trustedIdentities[address] = identityKey
        return if (existing != null && existing != identityKey) IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val existing = trustedIdentities[address]
        return existing == null || existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = trustedIdentities[address]

    override fun loadPreKey(preKeyId: Int) = preKeys[preKeyId] ?: error("Pre-key $preKeyId not found")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { preKeys[preKeyId] = record }
    override fun containsPreKey(preKeyId: Int) = preKeys.containsKey(preKeyId)
    override fun removePreKey(preKeyId: Int) { preKeys.remove(preKeyId) }

    override fun loadSignedPreKey(id: Int) = signedPreKeys[id] ?: error("Signed pre-key $id not found")
    override fun loadSignedPreKeys() = signedPreKeys.values.toMutableList()
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) { signedPreKeys[id] = record }
    override fun containsSignedPreKey(id: Int) = signedPreKeys.containsKey(id)
    override fun removeSignedPreKey(id: Int) { signedPreKeys.remove(id) }

    // KyberPreKeyStore (no-op — PQXDH not used)
    override fun loadKyberPreKey(id: Int) = kyberPreKeys[id] ?: error("No Kyber pre-key $id")
    override fun loadKyberPreKeys() = mutableListOf<org.signal.libsignal.protocol.state.KyberPreKeyRecord>()
    override fun storeKyberPreKey(id: Int, record: org.signal.libsignal.protocol.state.KyberPreKeyRecord) {}
    override fun containsKyberPreKey(id: Int) = false
    override fun markKyberPreKeyUsed(id: Int, spkId: Int, baseKey: ECPublicKey) {}

    // SessionStore
    override fun loadSession(address: SignalProtocolAddress) = sessions[address] ?: SessionRecord()
    override fun getSubDeviceSessions(name: String) = mutableListOf<Int>()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions[address] = record }
    override fun containsSession(address: SignalProtocolAddress) = sessions.containsKey(address)
    override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address) }
    override fun deleteAllSessions(name: String) { sessions.keys.filter { it.name == name }.forEach { sessions.remove(it) } }
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> {
        return addresses.mapTo(mutableListOf()) { sessions[it] ?: throw NoSessionException("No session for $it") }
    }

    companion object {
        fun generate(): TorentKeyStore {
            val pair = IdentityKeyPair.generate()
            val regId = KeyHelper.generateRegistrationId(false)
            return TorentKeyStore(pair, regId)
        }

        fun generatePreKeys(start: Int, count: Int) = (start until start + count).map { PreKeyRecord(it, ECKeyPair.generate()) }

        fun generateSignedPreKey(identity: IdentityKeyPair, id: Int): SignedPreKeyRecord {
            val kp = ECKeyPair.generate()
            val sig = identity.privateKey.calculateSignature(kp.publicKey.serialize())
            return SignedPreKeyRecord(id, System.currentTimeMillis(), kp, sig)
        }
    }
}
