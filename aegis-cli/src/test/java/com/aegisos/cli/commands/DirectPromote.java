package com.aegisos.cli.commands;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import com.aegisos.proto.ClientCommandResult;
import com.google.protobuf.ByteString;
import java.util.List;

public class DirectPromote {
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
                NodeId leader = node.consensus().leaderId();
                System.out.println("Leader discovered: " + leader.shortId());
                byte[] payload = HexUtil.decode(target);
                StateCommand cmd = StateCommand.newBuilder()
                        .setType(CommandType.ADD_VOTER)
                        .setPayload(ByteString.copyFrom(payload))
                        .build();
                System.out.println("Sending CLIENT_COMMAND directly to " + leader.shortId());
                byte[] cmdBytes = cmd.toByteArray();
                com.aegisos.core.message.AegisMessage reply = node.network().request(leader, com.aegisos.core.message.MessageType.CLIENT_COMMAND, cmdBytes, 10000).get();
                ClientCommandResult result = ClientCommandResult.parseFrom(reply.payload());
                System.out.println("Result success: " + result.getSuccess());
                System.out.println("Result error: " + result.getError());
                if (result.getLeaderId() != null && !result.getLeaderId().isEmpty()) {
                    System.out.println("Result leaderId: " + HexUtil.encode(result.getLeaderId().toByteArray()));
                } else {
                    System.out.println("Result leaderId is EMPTY");
                }
                return 0;
            } catch (Exception e) {
                e.printStackTrace();
                return 1;
            }
        });
    }
}
