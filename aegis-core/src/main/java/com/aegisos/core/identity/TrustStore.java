package com.aegisos.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local map of {@code NodeID -> PublicKey} for known peers.
 *
 * <p>Trust model (resolved open question Q2): Trust On First Use plus an optional
 * manual whitelist. When the whitelist is non-empty, only whitelisted node IDs are
 * accepted on first contact.
 */
public final class TrustStore {

    private static final Logger log = LoggerFactory.getLogger(TrustStore.class);

    private final ConcurrentHashMap<NodeId, byte[]> keys = new ConcurrentHashMap<>();
    private final Set<NodeId> whitelist = ConcurrentHashMap.newKeySet();
    private volatile boolean whitelistEnabled = false;

    public void enableWhitelist(Set<NodeId> allowed) {
        whitelist.clear();
        whitelist.addAll(allowed);
        whitelistEnabled = true;
    }

    public Optional<byte[]> publicKeyOf(NodeId id) {
        byte[] k = keys.get(id);
        return k == null ? Optional.empty() : Optional.of(k.clone());
    }

    public boolean isKnown(NodeId id) {
        return keys.containsKey(id);
    }

    /**
     * Records a (nodeId, publicKey) pair on first contact. Verifies that the key
     * actually hashes to the claimed node id, and rejects a key that conflicts with
     * a previously pinned key (TOFU pinning).
     *
     * @return true if the peer is trusted after this call
     */
    public boolean offerOnFirstUse(NodeId claimedId, byte[] publicKey) {
        NodeId derived = NodeId.of(com.aegisos.core.crypto.Hashing.sha256(publicKey));
        if (!derived.equals(claimedId)) {
            log.warn("Rejecting peer {}: node id does not match public key", claimedId.shortId());
            return false;
        }
        if (whitelistEnabled && !whitelist.contains(claimedId)) {
            log.warn("Rejecting peer {}: not on whitelist", claimedId.shortId());
            return false;
        }
        byte[] existing = keys.putIfAbsent(claimedId, publicKey.clone());
        if (existing != null && !java.util.Arrays.equals(existing, publicKey)) {
            log.error("Key conflict for {}: pinned key differs from offered key", claimedId.shortId());
            return false;
        }
        return true;
    }

    public void pin(NodeId id, byte[] publicKey) {
        keys.put(id, publicKey.clone());
    }
}
