package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.util.HexUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Phase8Test {

    private static ClusterHarness cluster;
    private static String artifactId;

    @BeforeAll
    static void setup() throws Exception {
        cluster = new ClusterHarness();
        cluster.start(3);
        
        ClusterHarness.await(10000, () -> cluster.nodes().stream()
                .allMatch(n -> n.consensus().leaderId() != null));

        // Wait for gossip to converge so AegisFS can satisfy replication factor 3
        ClusterHarness.await(5000, () -> cluster.nodes().get(0).discovery().membership().alivePeerIds().size() >= 2);

        // Use the existing aegis-demo-job-1.0.jar built earlier
        File demoJar = new File("../aegis-demo-job/target/aegis-demo-job-1.0.jar");
        byte[] jarBytes = Files.readAllBytes(demoJar.toPath());
        artifactId = HexUtil.encode(Hashing.sha256(jarBytes));

        com.aegisos.proto.ArtifactRecord record = com.aegisos.proto.ArtifactRecord.newBuilder()
                .setArtifactId(artifactId)
                .setFileName(demoJar.getName())
                .setSize(jarBytes.length)
                .setCreatedAt(System.currentTimeMillis())
                .setFsPath("/artifacts/" + artifactId)
                .setOwnerId(com.google.protobuf.ByteString.copyFrom(cluster.nodes().get(0).identity().nodeId().toBytes()))
                .setStatus(com.aegisos.proto.ArtifactStatus.ARTIFACT_ACTIVE)
                .build();

        cluster.nodes().get(0).fileSystem().write("/artifacts/" + artifactId, jarBytes);
        cluster.nodes().get(0).consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                .setPayload(record.toByteString())
                .build()).get(5, java.util.concurrent.TimeUnit.SECONDS);

        // wait for all nodes to apply REGISTER_ARTIFACT
        for (com.aegisos.node.AegisNode n : cluster.nodes()) {
            for (int j = 0; j < 50 && n.artifactRegistry().bySha256(artifactId).isEmpty(); j++) {
                Thread.sleep(50);
            }
        }
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void test100ArtifactJobs() throws Exception {
        int jobCount = 100;
        ExecutorService exec = Executors.newFixedThreadPool(10);
        List<Callable<Object>> tasks = new ArrayList<>();

        for (int i = 0; i < jobCount; i++) {
            final int index = i;
            tasks.add(() -> {
                // Submit to a random node in the cluster
                com.aegisos.node.AegisNode node = cluster.nodes().get(index % 3);

                JobHandle handle = node.api().getProcessManager().submitArtifact(
                        artifactId, "com.example.WordCounter", List.of("test " + index), 1, 512);
                
                return node.api().getProcessManager().awaitResult(handle, 30_000);
            });
        }

        List<Future<Object>> results = exec.invokeAll(tasks);
        exec.shutdown();

        int successes = 0;
        for (Future<Object> f : results) {
            Object res = f.get();
            if (res instanceof Long && (Long) res == 2L) {
                successes++;
            }
        }
        
        assertEquals(100, successes, "All 100 jobs should complete successfully");
    }
}
