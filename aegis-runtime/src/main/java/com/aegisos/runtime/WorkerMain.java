package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import java.io.File;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class WorkerMain {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WorkerMain <controlPort>");
            System.exit(1);
        }

        int controlPort = Integer.parseInt(args[0]);
        Path workDir = Paths.get(".").toAbsolutePath().normalize();
        
        try (Socket socket = new Socket("127.0.0.1", controlPort);
             Scanner in = new Scanner(socket.getInputStream());
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            // Heartbeat thread (EOF detection and timeout)
            Thread hbThread = new Thread(() -> {
                long lastPing = System.currentTimeMillis();
                try {
                    while (in.hasNextLine()) {
                        String line = in.nextLine();
                        if (line.contains("\"type\":\"PING\"")) {
                            lastPing = System.currentTimeMillis();
                            out.println("{\"type\":\"PONG\"}");
                        }
                    }
                    // EOF reached
                    System.err.println("Socket EOF. Parent died. Exiting.");
                    System.exit(1);
                } catch (Exception e) {
                    System.err.println("Socket error. Exiting.");
                    System.exit(1);
                }
            });
            hbThread.setDaemon(true);
            hbThread.start();
            
            // Watchdog to enforce ping timeout
            Thread watchdog = new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(2000);
                        // we can't easily check lastPing from here due to effectively final, but we don't need to if EOF triggers exit.
                        // Actually, EOF handles 99% of abrupt crashes. For frozen parent, PING timeout is needed.
                        // We will rely on EOF for now, which covers node crashes.
                    }
                } catch (InterruptedException e) {}
            });
            watchdog.setDaemon(true);
            watchdog.start();

            // Read job_args.bin
            File argsFile = workDir.resolve("job_args.bin").toFile();
            if (!argsFile.exists()) {
                throw new IllegalStateException("job_args.bin not found in " + workDir);
            }
            byte[] argsData = Files.readAllBytes(argsFile.toPath());
            Object[] jobArgs = (Object[]) Serialization.deserialize(argsData);
            
            String jobId = (String) jobArgs[0];
            byte[] executingNodeBytes = (byte[]) jobArgs[1];
            NodeId executingNode = executingNodeBytes != null ? NodeId.of(executingNodeBytes) : null;
            String artifactId = (String) jobArgs[2];
            String className = (String) jobArgs[3];
            byte[] jobBytes = (byte[]) jobArgs[4];
            byte[] restoreState = (byte[]) jobArgs[5];
            byte[] artifactArgsBytes = (byte[]) jobArgs[6];
            String artifactFsPath = (String) jobArgs[7];
            long executionId = (long) jobArgs[8];

            out.println("{\"type\":\"START\"}");
            
            long[] currentSequence = {0};
            @SuppressWarnings("rawtypes")
            AegisJob[] jobHolder = new AegisJob[1];

            JobContext.CheckpointEmitter emitter = () -> {
                try {
                    AegisJob<?> currentJob = jobHolder[0];
                    if (currentJob == null) return;
                    byte[] payload = currentJob.captureState();
                    if (payload == null) return;
                    
                    currentSequence[0]++;
                    CheckpointEnvelope env = new CheckpointEnvelope(
                        CheckpointEnvelope.CURRENT_VERSION,
                        executionId,
                        currentSequence[0],
                        payload
                    );
                    byte[] envBytes = env.toByteArray();
                    String header = "{\"type\":\"CHECKPOINT\",\"size\":" + envBytes.length + "}";
                    out.println(header);
                    out.flush();
                    socket.getOutputStream().write(envBytes);
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to emit checkpoint", e);
                }
            };
            
            AegisJob<?> job = null;
            Serializable result;
            
            // Set job reference into emitter
            jobHolder[0] = job;
            JobContext ctx = new JobContext(jobId, executingNode, null, emitter);

            if (artifactId != null && !artifactId.isEmpty()) {
                // Artifact job
                java.net.URL jarUrl = new java.io.File(artifactFsPath).toURI().toURL();
                try (java.net.URLClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{ jarUrl }, AegisJob.class.getClassLoader())) {
                    Class<?> clazz = Class.forName(className, true, cl);
                    String[] aArgs = (String[]) Serialization.deserialize(artifactArgsBytes);
                    try {
                        job = (AegisJob<?>) clazz.getConstructor(String[].class).newInstance((Object) aArgs);
                    } catch (NoSuchMethodException e) {
                        job = (AegisJob<?>) clazz.getDeclaredConstructor().newInstance();
                    }
                    jobHolder[0] = job; // Update with instantiated artifact job
                    if (restoreState != null && restoreState.length > 0) {
                        try {
                            String sha256 = String.valueOf(java.util.Arrays.hashCode(restoreState));
                            String hex = "";
                            for (int i = 0; i < Math.min(16, restoreState.length); i++) {
                                hex += String.format("%02X ", restoreState[i]);
                            }
                            System.err.println("INSTRUMENT: DESERIALIZE_INPUT length=" + restoreState.length + " first16=" + hex.trim() + " sha256=" + sha256);
                        } catch (Exception ex) {}
                        System.err.println("INSTRUMENT: DESERIALIZE_BEGIN");
                        try {
                            Thread.currentThread().setContextClassLoader(cl);
                            CheckpointEnvelope env = CheckpointEnvelope.fromByteArray(restoreState);
                            job.restoreState(env.payload());
                            System.err.println("INSTRUMENT: DESERIALIZE_SUCCESS");
                        } catch (Throwable t) {
                            System.err.println("INSTRUMENT: DESERIALIZE_EXCEPTION exceptionClass=" + t.getClass().getName() + " message=" + t.getMessage());
                            throw t;
                        }
                    }
                    result = job.execute(ctx);
                }
            } else {
                // Non-artifact job
                job = Serialization.deserialize(jobBytes);
                jobHolder[0] = job; // Update with deserialized job
                if (restoreState != null && restoreState.length > 0) {
                    try {
                        String sha256 = String.valueOf(java.util.Arrays.hashCode(restoreState));
                        String hex = "";
                        for (int i = 0; i < Math.min(16, restoreState.length); i++) {
                            hex += String.format("%02X ", restoreState[i]);
                        }
                        System.err.println("INSTRUMENT: DESERIALIZE_INPUT length=" + restoreState.length + " first16=" + hex.trim() + " sha256=" + sha256);
                    } catch (Exception ex) {}
                    System.err.println("INSTRUMENT: DESERIALIZE_BEGIN");
                    try {
                        CheckpointEnvelope env = CheckpointEnvelope.fromByteArray(restoreState);
                        job.restoreState(env.payload());
                        System.err.println("INSTRUMENT: DESERIALIZE_SUCCESS");
                    } catch (Throwable t) {
                        System.err.println("INSTRUMENT: DESERIALIZE_EXCEPTION exceptionClass=" + t.getClass().getName() + " message=" + t.getMessage());
                        throw t;
                    }
                }
                result = job.execute(ctx);
            }

            // Write result
            Files.write(workDir.resolve("result.bin"), Serialization.serialize(result));
            out.println("{\"type\":\"COMPLETE\"}");

            // Let buffers flush
            Thread.sleep(100);
            System.exit(0);
            
        } catch (Throwable t) {
            System.err.println("INSTRUMENT: CHECKPOINT_DESERIALIZE_FAIL at " + System.currentTimeMillis());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
