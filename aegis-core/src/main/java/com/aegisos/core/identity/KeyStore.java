package com.aegisos.core.identity;

import com.aegisos.core.crypto.Ed25519;
import com.aegisos.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Persists the node identity to disk.
 *
 * <p>Default location: {@code ~/.aegis/identity.key} (private, chmod 600 on POSIX)
 * and {@code ~/.aegis/identity.pub} (shareable). Encryption-at-rest of the private
 * key with a passphrase / OS keystore is a documented future concern (v0.3+).
 */
public final class KeyStore {

    private static final Logger log = LoggerFactory.getLogger(KeyStore.class);

    private final Path dir;
    private final Path privateFile;
    private final Path publicFile;

    public KeyStore(Path dir) {
        this.dir = dir;
        this.privateFile = dir.resolve("identity.key");
        this.publicFile = dir.resolve("identity.pub");
    }

    public static KeyStore defaultStore() {
        return new KeyStore(Path.of(System.getProperty("user.home"), ".aegis"));
    }

    public boolean exists() {
        return Files.exists(privateFile);
    }

    public Optional<NodeIdentity> load() {
        if (!exists()) {
            return Optional.empty();
        }
        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(privateFile)) {
                props.load(in);
            }
            byte[] priv = HexUtil.decode(props.getProperty("private"));
            byte[] pub = HexUtil.decode(props.getProperty("public"));
            long createdAt = Long.parseLong(props.getProperty("createdAt", "0"));
            return Optional.of(new NodeIdentity(priv, pub, createdAt));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load identity from " + privateFile, e);
        }
    }

    public void save(NodeIdentity identity) {
        try {
            Files.createDirectories(dir);
            Properties props = new Properties();
            props.setProperty("private", HexUtil.encode(extractPrivate(identity)));
            props.setProperty("public", HexUtil.encode(identity.publicKey()));
            props.setProperty("nodeId", identity.nodeId().toHex());
            props.setProperty("createdAt", Long.toString(identity.createdAt()));
            try (var out = Files.newOutputStream(privateFile)) {
                props.store(out, "AegisOS node identity - KEEP PRIVATE");
            }
            Files.writeString(publicFile, identity.nodeId().toHex() + "\n"
                    + HexUtil.encode(identity.publicKey()) + "\n");
            restrictPermissions(privateFile);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save identity to " + privateFile, e);
        }
    }

    private static byte[] extractPrivate(NodeIdentity identity) {
        // Same-package access to the raw seed; never exposed beyond this package.
        return identity.privateKey();
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows / non-POSIX filesystems: best effort only.
            log.debug("Could not set POSIX permissions on {} ({})", file, e.getMessage());
        }
    }

    /** Sanity helper for the Ed25519 key length, used by callers/tests. */
    public static int privateKeyLength() {
        return Ed25519.PRIVATE_BYTES;
    }
}
