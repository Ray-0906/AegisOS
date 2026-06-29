package com.aegisos.runtime.core;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.RuntimeEngine;
import com.aegisos.api.runtime.RuntimeManager;
import com.aegisos.runtime.table.InMemoryProcessTable;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.consensus.ProcessStateApplier;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.network.NetworkLayer;
import com.aegisos.core.observability.MetricsRegistry;
import com.aegisos.discovery.gossip.MembershipList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultRuntimeManagerTest {

    private RuntimeManager runtimeManager;
    private ProcessTable processTable;
    private ProcessStateApplier applier;
    private NodeId localNode;

    @BeforeEach
    public void setup() throws Exception {
        IdentityService identity = IdentityService.ephemeral();
        localNode = identity.nodeId();
        NetworkLayer network = new NetworkLayer(identity, 0, "127.0.0.1");

        Path tempDir = Files.createTempDirectory("raft-test");

        processTable = new InMemoryProcessTable();
        applier = new ProcessStateApplier(processTable);

        ConsensusModule consensus = new ConsensusModule(
            network,
            localNode, 
            tempDir,
            Collections::emptyList, 
            Collections::emptyList, 
            () -> true, 
            true, 
            10, 
            (id) -> null,
            new MetricsRegistry()
        ) {
            @Override
            public CompletableFuture<Long> propose(StateCommand command) {
                switch (command.getType()) {
                    case SUBMIT_PROCESS:
                        applier.applySubmit(command.getPayload().toByteArray());
                        break;
                    case UPDATE_PROCESS_STATE:
                        applier.applyUpdate(command.getPayload().toByteArray());
                        break;
                    case CANCEL_PROCESS:
                        applier.applyCancel(command.getPayload().toByteArray());
                        break;
                    default:
                        break;
                }
                return CompletableFuture.completedFuture(1L);
            }
        };

        MembershipList membership = new MembershipList(
            localNode,
            identity.publicKey(),
            "127.0.0.1:0",
            com.aegisos.proto.NodeRole.CLUSTER_MEMBER,
            1000,
            new com.aegisos.core.telemetry.ResourceMonitor(),
            null
        );

        com.aegisos.discovery.DiscoveryService discovery = org.mockito.Mockito.mock(com.aegisos.discovery.DiscoveryService.class);
        org.mockito.Mockito.when(discovery.membership()).thenReturn(membership);

        com.aegisos.fs.AegisFS fileSystem = new com.aegisos.fs.AegisFS(
            network, consensus, discovery, localNode, new byte[32], 1, tempDir.resolve("fs")
        );
        com.aegisos.runtime.ArtifactCache artifactCache = new com.aegisos.runtime.ArtifactCache(
            tempDir.resolve("cache"), fileSystem, 1024L * 1024L * 100L
        );
        com.aegisos.runtime.ArtifactRegistry artifactRegistry = new com.aegisos.runtime.ArtifactRegistry();
        
        com.aegisos.proto.ArtifactRecord artifactRecord = com.aegisos.proto.ArtifactRecord.newBuilder()
            .setArtifactId("test-artifact-id")
            .setFsPath("/test")
            .build();
        artifactRegistry.applyRegister(artifactRecord);

        Files.createDirectories(tempDir.resolve("cache"));
        Path localJar = tempDir.resolve("cache").resolve("test-artifact-id.jar");
        Files.write(localJar, new byte[10]);
        long size = Files.size(localJar);
        long mtime = Files.getLastModifiedTime(localJar).toMillis();
        Path localMeta = tempDir.resolve("cache").resolve("test-artifact-id.meta");
        Files.writeString(localMeta, size + "\n" + mtime + "\n");

        ProcessScheduler processScheduler = new SimpleProcessScheduler(consensus, identity, membership, processTable);
        RuntimeEngine runtimeEngine = new LocalRuntimeEngine(consensus, identity, artifactRegistry, artifactCache, network, processTable);
        
        processTable.addListener((com.aegisos.api.runtime.ProcessStateListener) processScheduler);
        processTable.addListener((com.aegisos.api.runtime.ProcessStateListener) runtimeEngine);

        runtimeManager = new DefaultRuntimeManager(processTable, processScheduler, runtimeEngine, consensus, identity);
    }

    @Test
    public void testSubmitProcessLifecycle() {
        ProcessResources resources = new ProcessResources(1, 64L);
        String processId = runtimeManager.submitProcess("test-artifact-id", null, null, "", null, null, null);

        ProcessRecord record = null;
        for (int i = 0; i < 50; i++) {
            record = runtimeManager.getProcessDetails(processId);
            if (record != null && (record.state() == ProcessState.RUNNING || record.state() == ProcessState.FAILED)) {
                break;
            }
            try { Thread.sleep(100); } catch (Exception e) {}
        }

        assertNotNull(record);
        assertTrue(record.state() == ProcessState.RUNNING || record.state() == ProcessState.FAILED, "Expected RUNNING or FAILED but was " + record.state());
        assertEquals(localNode.toHex(), record.ownerNodeId());
    }

    @Test
    public void testCancelProcess() {
        ProcessResources resources = new ProcessResources(1, 64L);
        String cmd = System.getProperty("os.name").toLowerCase().contains("win") ? "ping -n 10 127.0.0.1" : "sleep 10";
        String processId = runtimeManager.submitProcess("test-artifact-id", null, null, cmd, null, null, null);

        runtimeManager.cancelProcess(processId);

        ProcessRecord record = null;
        for (int i = 0; i < 50; i++) {
            record = runtimeManager.getProcessDetails(processId);
            if (record != null && record.state() == ProcessState.CANCELLED) {
                break;
            }
            try { Thread.sleep(100); } catch (Exception e) {}
        }

        assertNotNull(record);
        assertEquals(ProcessState.CANCELLED, record.state());
    }
}
