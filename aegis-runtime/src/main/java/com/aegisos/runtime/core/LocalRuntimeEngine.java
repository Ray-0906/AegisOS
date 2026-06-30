package com.aegisos.runtime.core;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.RuntimeEngine;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessStateListener;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.ArtifactCache;
import com.aegisos.runtime.ArtifactRegistry;
import com.aegisos.proto.ArtifactRecord;
import com.aegisos.runtime.util.ProcessMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.io.File;
import java.io.InputStream;
import com.aegisos.network.NetworkLayer;
import com.aegisos.network.VirtualOutputStream;
import com.aegisos.core.identity.NodeId;

public class LocalRuntimeEngine implements RuntimeEngine, ProcessStateListener {
    private static final Logger log = LoggerFactory.getLogger(LocalRuntimeEngine.class);
    
    private final ConsensusModule consensus;
    private final IdentityService identityService;
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactCache artifactCache;
    private final NetworkLayer networkLayer;
    private final com.aegisos.api.runtime.ProcessTable processTable;
    private final ExecutorService processExecutor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelledProcesses = ConcurrentHashMap.newKeySet();

    public LocalRuntimeEngine(ConsensusModule consensus, IdentityService identityService, 
                              ArtifactRegistry artifactRegistry, ArtifactCache artifactCache,
                              NetworkLayer networkLayer, com.aegisos.api.runtime.ProcessTable processTable) {
        this.consensus = consensus;
        this.identityService = identityService;
        this.artifactRegistry = artifactRegistry;
        this.artifactCache = artifactCache;
        this.networkLayer = networkLayer;
        this.processTable = processTable;
    }

    @Override
    public void start(ProcessRecord process) {
    }

    @Override
    public void stop(String processId) {
    }

    @Override
    public void pause(String processId) {
    }

    @Override
    public void checkpoint(String processId, byte[] stateData) {
        try {
            com.aegisos.proto.CheckpointStateProto proto = com.aegisos.proto.CheckpointStateProto.newBuilder()
                    .setProcessId(processId)
                    .setExecutionId("0") // placeholder
                    .setStateData(com.google.protobuf.ByteString.copyFrom(stateData))
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            StateCommand cmd = StateCommand.newBuilder()
                    .setType(CommandType.SAVE_CHECKPOINT)
                    .setPayload(proto.toByteString())
                    .build();

            consensus.propose(cmd).exceptionally(ex -> {
                log.error("Failed to propose checkpoint for process {}", processId, ex);
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to construct checkpoint command for process {}", processId, e);
        }
    }

    @Override
    public void onProcessStateChanged(ProcessRecord record) {
        if (record.state() == ProcessState.PLACED && identityService.nodeId().toHex().equals(record.ownerNodeId())) {
            log.info("[Engine Daemon] Reacting to PLACED: {}", record.processId());
            processExecutor.submit(() -> executeProcess(record));
        } else if (record.state() == ProcessState.CANCELLED) {
            Process process = activeProcesses.remove(record.processId());
            if (process != null) {
                cancelledProcesses.add(record.processId());
                process.destroyForcibly();
                log.info("Forcefully terminated process {}", record.processId());
            }
        }
    }

    private void executeProcess(ProcessRecord record) {
        String traceTag = (record.traceId() != null && !record.traceId().isEmpty()) ? "[TRACE:" + record.traceId() + "] " : "";
        try {
            log.info("{}Starting process {} (Artifact: {})", traceTag, record.processId(), record.artifactId());
            ArtifactRecord artifact = artifactRegistry.bySha256(record.artifactId())
                    .orElseThrow(() -> new IllegalStateException("Artifact not found in registry: " + record.artifactId()));
            
            Path localPath = artifactCache.resolve(record.artifactId(), artifact.getFsPath());
            Path logDir = localPath.getParent().resolve("logs");
            Files.createDirectories(logDir);
            File logFile = logDir.resolve(record.processId() + ".log").toFile();

            byte[] checkpoint = processTable.getLatestCheckpoint(record.processId());
            if (checkpoint != null) {
                Path checkpointFile = logDir.resolve("checkpoint.dat");
                Files.write(checkpointFile, checkpoint);
            }

            ProcessBuilder pb;
            if (record.executionCommand() == null || record.executionCommand().trim().isEmpty()) {
                pb = new ProcessBuilder("java", "-jar", localPath.toString());
            } else {
                String cmdStr = record.executionCommand().replace("{artifact}", localPath.toString());
                pb = new ProcessBuilder(cmdStr.split(" "));
            }
            
            if (checkpoint != null) {
                pb.environment().put("AEGIS_CHECKPOINT_DIR", logDir.toString());
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            activeProcesses.put(record.processId(), process);

            String actualPipeTargetId = record.pipeToProcessId();
            if (record.pipeToService() != null && !record.pipeToService().isEmpty()) {
                java.util.Optional<ProcessRecord> resolved = processTable.lookupService(record.pipeToService());
                if (resolved.isPresent()) {
                    actualPipeTargetId = resolved.get().processId();
                    log.info("{}DNS_RESOLVED: Service '{}' -> Process '{}'", traceTag, record.pipeToService(), actualPipeTargetId);
                } else {
                    throw new RuntimeException("DNS_RESOLUTION_FAILED: Service '" + record.pipeToService() + "' not found in cluster");
                }
            }

            final String resolvedPipeTargetId = actualPipeTargetId;

            InputStream processStdout = process.getInputStream();
            log.info("{}Established IPC data pump for process {}", traceTag, record.processId());
            CompletableFuture.runAsync(() -> {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = processStdout.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        fos.flush();

                        if (resolvedPipeTargetId != null && !resolvedPipeTargetId.isEmpty()) {
                            java.util.Optional<ProcessRecord> targetOpt = processTable.lookup(resolvedPipeTargetId);
                            if (targetOpt.isPresent() && targetOpt.get().ownerNodeId() != null) {
                                byte[] data = java.util.Arrays.copyOf(buf, read);
                                networkLayer.sendIpcData(targetOpt.get().ownerNodeId(), resolvedPipeTargetId, data);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to pump process stream for {}", record.processId(), e);
                }
            }, processExecutor);

            process.onExit().thenAccept(p -> {
                activeProcesses.remove(record.processId());
                if (cancelledProcesses.remove(record.processId())) {
                    return;
                }
                log.info("{}Process {} exited with code {}", traceTag, record.processId(), p.exitValue());
                ProcessState exitState = p.exitValue() == 0 ? ProcessState.COMPLETED : ProcessState.FAILED;
                
                ProcessRecord exitRecord = new ProcessRecord(
                        record.processId(),
                        record.artifactId(),
                        record.ownerNodeId(),
                        record.submitterNodeId(),
                        record.executionId(),
                        exitState,
                        record.resources(),
                        record.submitTimestamp(),
                        System.currentTimeMillis(),
                        record.executionCommand(),
                        record.pipeToProcessId(),
                        record.resourceConstraints(),
                        record.placementConstraints(),
                        record.serviceName(),
                        record.pipeToService(),
                        record.traceId()
                );

                try {
                    ProcessRecordProto proto = ProcessMapper.toProto(exitRecord);
                    StateCommand cmd = StateCommand.newBuilder()
                            .setType(CommandType.UPDATE_PROCESS_STATE)
                            .setPayload(proto.toByteString())
                            .build();

                    consensus.propose(cmd).exceptionally(ex -> {
                        log.error("Failed to propose exit state for process {}", record.processId(), ex);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("Failed to serialize exit state for process {}", record.processId(), e);
                }
            });

            ProcessRecord runningRecord = new ProcessRecord(
                    record.processId(),
                    record.artifactId(),
                    record.ownerNodeId(),
                    record.submitterNodeId(),
                    record.executionId(),
                    ProcessState.RUNNING,
                    record.resources(),
                    record.submitTimestamp(),
                    System.currentTimeMillis(),
                    record.executionCommand(),
                    record.pipeToProcessId(),
                    record.resourceConstraints(),
                    record.placementConstraints(),
                    record.serviceName(),
                    record.pipeToService(),
                    record.traceId()
            );

            ProcessRecordProto proto = ProcessMapper.toProto(runningRecord);
            StateCommand cmd = StateCommand.newBuilder()
                    .setType(CommandType.UPDATE_PROCESS_STATE)
                    .setPayload(proto.toByteString())
                    .build();

            consensus.propose(cmd).exceptionally(ex -> {
                log.error("Failed to propose RUNNING state for process {}", record.processId(), ex);
                return null;
            });
        } catch (Exception e) {
            log.error("{}Process {} failed to start", traceTag, record.processId(), e);
        }
    }

    public void receiveIpcData(String processId, byte[] data) {
        Process process = activeProcesses.get(processId);
        if (process != null && process.isAlive()) {
            try {
                process.getOutputStream().write(data);
                process.getOutputStream().flush();
            } catch (java.io.IOException e) {
                log.error("Failed to write IPC data to process {}", processId, e);
            }
        }
    }
}
