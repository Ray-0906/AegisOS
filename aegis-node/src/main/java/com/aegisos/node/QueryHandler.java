package com.aegisos.node;

import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.proto.ClientQuery;
import com.aegisos.proto.ClientQueryResult;
import com.aegisos.proto.MembershipList;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming read-only queries from AegisClient (CLI).
 * <p>
 * STRICT INVARIANT: Handlers in this class must NEVER mutate state, append to Raft,
 * trigger replication, or modify metadata. Violation of this invariant is a correctness bug.
 */
public final class QueryHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryHandler.class);

    private final DiscoveryService discovery;
    private final com.aegisos.fs.AegisFS fs;

    public QueryHandler(DiscoveryService discovery, com.aegisos.fs.AegisFS fs) {
        this.discovery = discovery;
        this.fs = fs;
    }

    public AegisMessage handle(AegisMessage msg) {
        try {
            ClientQuery query = ClientQuery.parseFrom(msg.payload());
            ClientQueryResult.Builder result = ClientQueryResult.newBuilder();

            switch (query.getType()) {
                case LIST_NODES:
                    MembershipList ml = MembershipList.newBuilder()
                            .addAllPeers(discovery.membership().allPeers())
                            .build();
                    result.setPayload(ml.toByteString());
                    break;
                case LOCAL_CHUNKS:
                    if (fs != null && fs.chunkStore() != null) {
                        String joined = String.join(",", fs.chunkStore().listChunkIds());
                        result.setPayload(ByteString.copyFromUtf8(joined));
                    } else {
                        result.setPayload(ByteString.EMPTY);
                    }
                    break;

                default:
                    result.setError("Unsupported query type: " + query.getType());
                    break;
            }

            return new AegisMessage(
                    msg.recipient(), // Swapped: recipient of original is sender of response
                    msg.sender(),
                    MessageType.CLIENT_QUERY_RESULT,
                    result.build().toByteArray()
            );

        } catch (Exception e) {
            log.error("Failed to handle client query", e);
            ClientQueryResult errorResult = ClientQueryResult.newBuilder()
                    .setError(e.toString())
                    .build();
            return new AegisMessage(
                    msg.recipient(),
                    msg.sender(),
                    MessageType.CLIENT_QUERY_RESULT,
                    errorResult.toByteArray()
            );
        }
    }
}
