# AegisOS Security Model

## Identity

- Each node owns an Ed25519 keypair generated on first start and persisted to
  `~/.aegis/identity.key` with owner-only permissions (chmod 600 equivalent).
- A node's `NodeId` is `SHA-256(publicKey)` — 32 bytes. Identity is therefore
  self-certifying: knowing the id lets you verify any message the node signs.

## Trust (TOFU + whitelist)

- Trust On First Use: the first time we see a peer's public key we remember it in the
  `TrustStore`. Subsequent connections must present the same key for that `NodeId`, or the
  connection is rejected (key-substitution defense).
- A manual whitelist can pre-seed trusted keys for stricter deployments.

## Handshake

Mutual, authenticated, forward-secret:

1. `Hello` — each side sends an ephemeral X25519 public key plus its long-term Ed25519
   identity key, signed by that identity key.
2. ECDH over the ephemeral keys yields a shared secret; HKDF-SHA256 derives the AES-256
   session key. Ephemeral keys give per-session forward secrecy.
3. `Verify` — each side proves liveness/possession by signing the transcript. Identity is
   checked against the `TrustStore`.

## Channel encryption

- AES-256-GCM. A fresh 12-byte random nonce per record (never reused under a key); GCM
  provides confidentiality + integrity (authentication tag).

## Replay defense

Every inbound application message carries a timestamp and nonce. `ReplayGuard` rejects:

- messages whose timestamp is outside a ±30s window, and
- any nonce seen before within the window.

## What v0.1 does NOT protect against

- Compromise of a node's on-disk private key.
- Malicious-but-trusted nodes (no Byzantine fault tolerance; Raft assumes crash-stop).
- Traffic analysis / metadata leakage at the network layer.
