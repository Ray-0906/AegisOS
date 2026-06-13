package com.aegisos.cli.commands;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import java.util.List;

public class Promote {
    public static void main(String[] args) throws Exception {
        String target = args[0];
        ClientCommands.withClient(List.of("127.0.0.1:9001"), node -> {
            try {
                System.out.println("Waiting for leader...");
                long start = System.currentTimeMillis();
                while (node.consensus().leaderId() == null) {
                    Thread.sleep(100);
                    if (System.currentTimeMillis() - start > 10000) {
                        throw new RuntimeException("Timeout waiting for leader");
                    }
                }
                System.out.println("Leader discovered: " + node.consensus().leaderId().shortId());
                byte[] payload = HexUtil.decode(target);
                StateCommand cmd = StateCommand.newBuilder()
                        .setType(CommandType.ADD_VOTER)
                        .setPayload(ByteString.copyFrom(payload))
                        .build();
                System.out.println("Proposing ADD_VOTER for " + target);
                long idx = node.consensus().propose(cmd).get();
                System.out.println("Success! Committed at index " + idx);
                return 0;
            } catch (Exception e) {
                e.printStackTrace();
                return 1;
            }
        });
    }
}
