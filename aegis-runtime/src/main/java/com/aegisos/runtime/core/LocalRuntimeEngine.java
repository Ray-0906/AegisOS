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
import java.io.File;

public class LocalRuntimeEngine implements RuntimeEngine, ProcessStateListener {
    private static final Logger log = LoggerFactory.getLogger(LocalRuntimeEngine.class);
    
    private final ConsensusModule consensus;
    private final IdentityService identityService;
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactCache artifactCache;
    private final ExecutorService processExecutor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelledProcesses = ConcurrentHashMap.newKeySet();

    public LocalRuntimeEngine(ConsensusModule consensus, IdentityService identityService, 
                              ArtifactRegistry artifactRegistry, ArtifactCache artifactCache) {
        this.consensus = consensus;
        this.identityService = identityService;
        this.artifactRegistry = artifactRegistry;
        this.artifactCache = artifactCache;
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
    public void checkpoint(String processId) {
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
        try {
            ArtifactRecord artifact = artifactRegistry.bySha256(record.artifactId())
                    .orElseThrow(() -> new IllegalStateException("Artifact not found in registry: " + record.artifactId()));
            
            Path localPath = artifactCache.resolve(record.artifactId(), artifact.getFsPath());
            Path logDir = localPath.getParent().resolve("logs");
            Files.createDirectories(logDir);
            File logFile = logDir.resolve(record.processId() + ".log").toFile();

            ProcessBuilder pb = new ProcessBuilder("java", "-jar", localPath.toString());
            pb.redirectOutput(logFile);
            pb.redirectError(logFile);
            Process process = pb.start();
            activeProcesses.put(record.processId(), process);

            process.onExit().thenAccept(p -> {
                activeProcesses.remove(record.processId());
                if (cancelledProcesses.remove(record.processId())) {
                    return;
                }
                ProcessState exitState = p.exitValue() == 0 ? ProcessState.COMPLETED : ProcessState.FAILED;
                
                ProcessRecord exitRecord = new ProcessRecord(
                        record.processId(),
                        record.artifactId(),
                        record.ownerNodeId(),
                        record.executionId(),
                        exitState,
                        record.resources(),
                        record.submitTimestamp(),
                        System.currentTimeMillis()
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
                    record.executionId(),
                    ProcessState.RUNNING,
                    record.resources(),
                    record.submitTimestamp(),
                    System.currentTimeMillis()
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
            log.error("Failed to execute process {}", record.processId(), e);
        }
    }
}
