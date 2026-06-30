package com.aegisos.cli.client;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.KeyStore;
import com.aegisos.core.model.Endpoint;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.ClientQuery;
import com.aegisos.proto.ClientQueryResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight client for interacting with an AegisOS cluster.
 * 
 * Unlike AegisNode, this client ONLY initializes the crypto and transport layers.
 * It does NOT start Gossip or Consensus, guaranteeing it will never pollute the 
 * cluster's membership or Raft quorum.
 */
public class AegisClient implements AutoCloseable {

    private final Path tempDir;
    private final IdentityService identity;
    private final NetworkLayer network;

    public AegisClient() throws IOException {
        this.tempDir = Files.createTempDirectory("aegis-cli-client-");
        KeyStore keyStore = new KeyStore(tempDir);
        this.identity = IdentityService.bootstrap(keyStore);
        this.network = new NetworkLayer(identity, new com.aegisos.core.security.IdentityManager(java.nio.file.Path.of(System.getProperty("user.home"), ".aegis")), 0, "127.0.0.1");
    }

    public void start() throws IOException {
        network.start();
    }

    public ClientQueryResult query(List<String> seeds, ClientQuery query) throws Exception {
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("At least one seed is required");
        }
        
        Exception lastException = null;
        for (String seedStr : seeds) {
            try {
                Endpoint seed = Endpoint.parse(seedStr);
                // Connect to the seed; the NetworkLayer performs the TOFU handshake and returns the peer's NodeId
                com.aegisos.core.identity.NodeId targetId = network.connect(seed);
                
                AegisMessage response = network.request(
                        targetId,
                        MessageType.CLIENT_QUERY,
                        query.toByteArray()
                ).get(5, TimeUnit.SECONDS);

                return ClientQueryResult.parseFrom(response.payload());
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RuntimeException("Failed to query any seed", lastException);
    }

    @Override
    public void close() {
        network.close();
        deleteRecursive(tempDir.toFile());
    }

    private static void deleteRecursive(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
