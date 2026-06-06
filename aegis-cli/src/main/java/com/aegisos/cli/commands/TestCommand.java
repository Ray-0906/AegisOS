package com.aegisos.cli.commands;

import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import com.aegisos.proto.AddReplica;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.NodeRole;
import com.aegisos.proto.RemoveReplica;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import picocli.CommandLine;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "test-cmd", hidden = true, description = "Internal command for integration tests.")
public class TestCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--seed"}, description = "Cluster seed nodes")
    private List<String> seeds = List.of();

    @CommandLine.Parameters(index = "0", description = "Test action (e.g., add-replica, remove-replica)")
    private String action;

    @CommandLine.Parameters(index = "1", description = "File path")
    private String filePath;

    @CommandLine.Parameters(index = "2", description = "Node directory (e.g. data/node4) to read node ID from")
    private String nodeDir;

    @Override
    public Integer call() throws Exception {
        NodeConfig config = new NodeConfig()
                .port(0)
                .apiPort(0)
                .homeDir(Files.createTempDirectory("aegis-client-"))
                .role(NodeRole.CLIENT);
                
        for (String seed : seeds) {
            config.addSeed(com.aegisos.core.model.Endpoint.parse(seed));
        }

        try (AegisNode node = new AegisNode(config)) {
            node.start();
            Thread.sleep(2000); // Wait for discovery

            if ("count-all".equals(action)) {
                System.out.println("METADATA_COUNT=" + node.fileSystem().fileIndex().all().size());
                return 0;
            }

            FileMetadata meta = node.fileSystem().list("").stream()
                    .filter(m -> m.getName().equals(filePath))
                    .findFirst()
                    .orElse(null);
            if (meta == null) {
                System.err.println("File not found: " + filePath);
                return 1;
            }

            byte[] fileId = meta.getFileId().toByteArray();
            byte[] chunkId = meta.getChunks(0).getChunkId().toByteArray(); // just test chunk 0
            
            String nodeIdHex = new String(Files.readAllBytes(java.nio.file.Path.of(nodeDir, "node.id"))).trim();
            byte[] nodeId = HexUtil.decode(nodeIdHex);

            StateCommand.Builder cmdBuilder = StateCommand.newBuilder();

            if ("add-replica".equals(action)) {
                cmdBuilder.setType(CommandType.ADD_REPLICA)
                        .setPayload(AddReplica.newBuilder()
                                .setFileId(ByteString.copyFrom(fileId))
                                .setChunkId(ByteString.copyFrom(chunkId))
                                .setNodeId(ByteString.copyFrom(nodeId))
                                .build().toByteString());
            } else if ("remove-replica".equals(action)) {
                cmdBuilder.setType(CommandType.REMOVE_REPLICA)
                        .setPayload(RemoveReplica.newBuilder()
                                .setFileId(ByteString.copyFrom(fileId))
                                .setChunkId(ByteString.copyFrom(chunkId))
                                .setNodeId(ByteString.copyFrom(nodeId))
                                .build().toByteString());
            } else {
                System.err.println("Unknown action: " + action);
                return 1;
            }

            node.consensus().propose(cmdBuilder.build()).get();
            System.out.println("Command proposed and committed successfully.");
            return 0;
        }
    }
}
