package com.aegisos.discovery;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.model.Endpoint;
import com.aegisos.discovery.dht.KademliaRouter;
import com.aegisos.discovery.dht.RoutingTable;
import com.aegisos.discovery.gossip.GossipProtocol;
import com.aegisos.discovery.gossip.MembershipList;
import com.aegisos.network.NetworkLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discovery layer (design section 3.3): seed bootstrap, gossip membership, and the
 * Kademlia routing table. Also installs the network address resolver so the transport
 * can dial peers it learns about through gossip.
 */
public final class DiscoveryService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    public static final int DEFAULT_FANOUT = 3;
    public static final long DEFAULT_INTERVAL_MS = 1_000;

    private final NetworkLayer network;
    private final IdentityService identity;
    private final MembershipList membership;
    private final GossipProtocol gossip;
    private final RoutingTable routingTable;
    private final KademliaRouter router;
    private final ScheduledExecutorService maintenance =
            com.aegisos.core.ExecutorRegistry.register("discoveryMaint", Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-discovery-maint");
                t.setDaemon(true);
                return t;
            }));

    public DiscoveryService(NetworkLayer network, IdentityService identity, String selfAddress, com.aegisos.proto.NodeRole role) {
        this.network = network;
        this.identity = identity;
        this.membership = new MembershipList(identity.nodeId(), identity.publicKey(),
                selfAddress, role, DEFAULT_INTERVAL_MS);
        this.gossip = new GossipProtocol(network, membership, DEFAULT_FANOUT, DEFAULT_INTERVAL_MS);
        this.routingTable = new RoutingTable(identity.nodeId());
        this.router = new KademliaRouter(network, routingTable,
                id -> membership.publicKeyOf(id).orElse(null),
                id -> membership.endpointOf(id).map(Endpoint::toString).orElse(null));
    }

    public void start(List<Endpoint> seeds) {
        network.setAddressResolver(membership::endpointOf);
        gossip.start();
        router.start();
        for (Endpoint seed : seeds) {
            connectToSeed(seed);
        }
        maintenance.scheduleAtFixedRate(this::syncRoutingTable,
                com.aegisos.core.SchedulerJitter.jitter(DEFAULT_INTERVAL_MS, DEFAULT_INTERVAL_MS), DEFAULT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.debug("Discovery started with {} seed(s)", seeds.size());
    }

    private void connectToSeed(Endpoint seed) {
        log.debug("Connecting to seed {}", seed);
        try {
            NodeId peerId = network.connect(seed);
            Optional<byte[]> key = identity.getPublicKey(peerId);
            key.ifPresent(k -> {
                membership.observe(peerId, k, seed);
                routingTable.add(peerId);
            });
            log.debug("Connected to seed {} ({})", seed, peerId.shortId());
        } catch (IOException e) {
            log.warn("DISCOVERY FAIL: Could not reach seed {}: {}", seed, e.getMessage());
        }
    }

    private void syncRoutingTable() {
        membership.alivePeerIds().forEach(routingTable::add);
    }

    public MembershipList membership() {
        return membership;
    }

    public KademliaRouter router() {
        return router;
    }

    @Override
    public void close() {
        maintenance.shutdownNow();
        gossip.close();
    }
}
