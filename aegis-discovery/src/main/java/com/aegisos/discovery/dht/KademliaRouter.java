package com.aegisos.discovery.dht;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.FindNode;
import com.aegisos.proto.FindNodeResult;
import com.aegisos.proto.PeerEntry;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * Content routing over the {@link RoutingTable}. Answers FIND_NODE queries with the
 * closest known nodes to a target id. Used by AegisFS to pick chunk-placement targets
 * and to locate replicas.
 */
public final class KademliaRouter {

    private static final Logger log = LoggerFactory.getLogger(KademliaRouter.class);

    private final NetworkLayer network;
    private final RoutingTable table;
    private final Function<NodeId, byte[]> publicKeyResolver;
    private final Function<NodeId, String> addressResolver;

    public KademliaRouter(NetworkLayer network, RoutingTable table,
                          Function<NodeId, byte[]> publicKeyResolver,
                          Function<NodeId, String> addressResolver) {
        this.network = network;
        this.table = table;
        this.publicKeyResolver = publicKeyResolver;
        this.addressResolver = addressResolver;
    }

    public void start() {
        network.registerHandler(MessageType.FIND_NODE, this::onFindNode);
    }

    public RoutingTable table() {
        return table;
    }

    /** Local lookup of the closest known nodes to {@code target}. */
    public List<NodeId> findClosest(NodeId target, int count) {
        return table.closest(target, count);
    }

    private AegisMessage onFindNode(AegisMessage request) {
        try {
            FindNode q = FindNode.parseFrom(request.payload());
            NodeId target = NodeId.of(q.getTarget().toByteArray());
            FindNodeResult.Builder result = FindNodeResult.newBuilder();
            for (NodeId near : table.closest(target, RoutingTable.K)) {
                byte[] key = publicKeyResolver.apply(near);
                String addr = addressResolver.apply(near);
                if (key == null || addr == null) {
                    continue;
                }
                result.addClosest(PeerEntry.newBuilder()
                        .setNodeId(ByteString.copyFrom(near.toBytes()))
                        .setPublicKey(ByteString.copyFrom(key))
                        .setAddress(addr)
                        .build());
            }
            return new AegisMessage(null, request.sender(), MessageType.FIND_NODE_RESULT,
                    result.build().toByteArray());
        } catch (Exception e) {
            log.warn("FIND_NODE handling failed: {}", e.toString());
            return new AegisMessage(null, request.sender(), MessageType.FIND_NODE_RESULT,
                    FindNodeResult.getDefaultInstance().toByteArray());
        }
    }
}
