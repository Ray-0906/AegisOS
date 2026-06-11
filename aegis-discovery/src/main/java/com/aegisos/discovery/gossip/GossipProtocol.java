package com.aegisos.discovery.gossip;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.NetworkLayer;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Push-pull gossip (design section 3.3). Every {@code intervalMs}:
 * <ol>
 *   <li>refresh our own entry;</li>
 *   <li>pick K random ALIVE peers;</li>
 *   <li>send each our membership snapshot (GOSSIP_SYN) and merge their reply (GOSSIP_ACK);</li>
 *   <li>sweep stale peers through SUSPECT/DEAD.</li>
 * </ol>
 */
public final class GossipProtocol implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GossipProtocol.class);

    private final NetworkLayer network;
    private final MembershipList membership;
    private final int fanout;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-gossip");
                t.setDaemon(true);
                return t;
            });

    public GossipProtocol(NetworkLayer network, MembershipList membership, int fanout, long intervalMs) {
        this.network = network;
        this.membership = membership;
        this.fanout = fanout;
        this.intervalMs = intervalMs;
    }

    public void start() {
        network.registerHandler(MessageType.GOSSIP_SYN, this::onSyn);
        scheduler.scheduleAtFixedRate(this::cycleSafe, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Gossip started (fanout={}, interval={}ms)", fanout, intervalMs);
    }

    private AegisMessage onSyn(AegisMessage request) {
        try {
            membership.merge(com.aegisos.proto.MembershipList.parseFrom(request.payload()));
        } catch (InvalidProtocolBufferException e) {
            log.warn("Malformed gossip SYN from {}", request.sender().shortId());
        }
        return new AegisMessage(null, request.sender(), MessageType.GOSSIP_ACK,
                membership.snapshot().toByteArray());
    }

    private void cycleSafe() {
        try {
            cycle();
        } catch (Exception e) {
            log.warn("Gossip cycle error: {}", e.toString());
        }
    }

    private void cycle() {
        membership.touchSelf();
        List<NodeId> alive = membership.alivePeerIds();
        Collections.shuffle(alive, ThreadLocalRandom.current());
        int k = Math.min(fanout, alive.size());
        byte[] snapshot = membership.snapshot().toByteArray();
        for (int i = 0; i < k; i++) {
            NodeId peer = alive.get(i);
            network.request(peer, MessageType.GOSSIP_SYN, snapshot)
                    .whenComplete((ack, err) -> {
                        if (err != null) {
                            return;
                        }
                        try {
                            membership.merge(com.aegisos.proto.MembershipList.parseFrom(ack.payload()));
                        } catch (InvalidProtocolBufferException e) {
                            log.debug("Bad gossip ACK from {}", peer.shortId());
                        }
                    });
        }
        membership.sweep();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
