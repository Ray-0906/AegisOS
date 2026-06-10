package com.aegisos.consensus;

import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.AppendEntries;
import com.aegisos.proto.AppendEntriesResult;
import com.aegisos.proto.RequestVote;
import com.aegisos.proto.RequestVoteResult;
import com.aegisos.proto.InstallSnapshot;
import com.aegisos.proto.InstallSnapshotResponse;

import java.util.concurrent.CompletableFuture;

/** Outbound Raft RPCs. Decouples {@link RaftNode} from the concrete network transport. */
public interface RaftTransport {

    CompletableFuture<RequestVoteResult> sendRequestVote(NodeId peer, RequestVote request);

    CompletableFuture<AppendEntriesResult> sendAppendEntries(NodeId peer, AppendEntries request);

    CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(NodeId peer, InstallSnapshot request);
}
