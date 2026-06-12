package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ProcessSupervisor {
    private static final Logger log = LoggerFactory.getLogger(ProcessSupervisor.class);

    private final String jobId;
    private final WorkspaceInfo workspace;
    private final Path workDir;
    private final int memoryMb;
    private Process process;
    private final AtomicBoolean killed = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private PrintWriter out;
    private java.io.DataInputStream in;

    private java.util.function.Consumer<byte[]> checkpointListener;

    public ProcessSupervisor(String jobId, int memoryMb, WorkspaceInfo workspace) {
        this.jobId = jobId;
        this.memoryMb = memoryMb;
        this.workspace = workspace;
        this.workDir = workspace.root();
    }

    public void setCheckpointListener(java.util.function.Consumer<byte[]> checkpointListener) {
        this.checkpointListener = checkpointListener;
    }

    public void mountArtifacts(java.util.Map<String, String> mountPaths) throws IOException {
        if (mountPaths == null || mountPaths.isEmpty()) return;
        for (java.util.Map.Entry<String, String> entry : mountPaths.entrySet()) {
            String mountPath = entry.getKey();
            String localCachePath = entry.getValue();

            // Validate mount path
            if (mountPath == null || mountPath.isEmpty() || mountPath.equals(".") || mountPath.equals("..") ||
                mountPath.startsWith("/") || mountPath.contains("..")) {
                throw new IllegalArgumentException("Invalid mount path: " + mountPath);
            }

            java.nio.file.Path targetPath = workspace.artifactsDir().resolve(mountPath).normalize();
            if (!targetPath.startsWith(workspace.artifactsDir())) {
                throw new IllegalArgumentException("Mount path escapes artifacts directory: " + mountPath);
            }

            java.nio.file.Files.createDirectories(targetPath.getParent());

            // Try symlink, fallback to copy
            try {
                java.nio.file.Files.createSymbolicLink(targetPath, java.nio.file.Paths.get(localCachePath));
                log.info("Symlinked artifact {} to {}", localCachePath, targetPath);
            } catch (Exception e) {
                log.warn("Failed to create symlink for {}, falling back to copy", mountPath);
                java.nio.file.Files.copy(java.nio.file.Paths.get(localCachePath), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied artifact {} to {}", localCachePath, targetPath);
            }
        }
    }

    public byte[] runWorker(Object[] jobArgs) throws Exception {
        workspace.provision();

        File argsFile = workDir.resolve("job_args.bin").toFile();
        Files.write(argsFile.toPath(), Serialization.serialize(jobArgs));

        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        String rawClasspath = System.getProperty("java.class.path");
        String classpath = java.util.Arrays.stream(rawClasspath.split(File.pathSeparator))
                .map(p -> new File(p).getAbsolutePath())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));

        StringBuilder sb = new StringBuilder("Types: ");
        for (Object o : jobArgs) {
            sb.append(o == null ? "null" : o.getClass().getName()).append(", ");
        }
        log.info(sb.toString());

        ProcessBuilder pb = new ProcessBuilder(
            javaBin,
            "-Xmx" + (memoryMb > 0 ? memoryMb : 512) + "m",
            "-cp", classpath,
            "com.aegisos.runtime.WorkerMain",
            String.valueOf(port)
        );
        pb.directory(workDir.toFile());
        
        File stdoutFile = workspace.stdoutLog().toFile();
        File stderrFile = workspace.stderrLog().toFile();
        pb.redirectOutput(stdoutFile);
        pb.redirectError(stderrFile);

        log.info("Supervisor starting Worker process for job {}", jobId);
        process = pb.start();

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<String> failError = new AtomicReference<>(null);
        AtomicReference<String> controlError = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        Thread acceptor = new Thread(() -> {
            try {
                serverSocket.setSoTimeout(10000); // 10 seconds to connect
                clientSocket = serverSocket.accept();
                log.info("[{}] Worker connected to control socket", jobId);
                // Do NOT set a read timeout on the client socket.
                // Scanner treats SocketTimeoutException as end-of-input,
                // which silently kills the acceptor loop.
                // Use DataInputStream to avoid buffering ahead into the binary payload
                in = new java.io.DataInputStream(clientSocket.getInputStream());
                out = new PrintWriter(clientSocket.getOutputStream(), true);

                // Start ping thread
                Thread pinger = new Thread(() -> {
                    try {
                        while (!completed.get() && process.isAlive()) {
                            if (out != null) {
                                out.println("{\"type\":\"PING\"}");
                            }
                            Thread.sleep(2000);
                        }
                        log.info("[{}] Pinger exiting: completed={} processAlive={}", jobId, completed.get(), process.isAlive());
                    } catch (Exception e) {
                        log.debug("[{}] Pinger exception: {}", jobId, e.getMessage());
                    }
                });
                pinger.setDaemon(true);
                pinger.start();

                @SuppressWarnings("deprecation")
                String line;
                while (!completed.get() && (line = in.readLine()) != null) {
                    log.debug("[{}] Acceptor read: {}", jobId, line);
                    if (line.contains("\"type\":\"COMPLETE\"")) {
                        completed.set(true);
                        latch.countDown();
                        break;
                    } else if (line.contains("\"type\":\"FAIL\"")) {
                        failError.set("Worker sent FAIL");
                        completed.set(true);
                        latch.countDown();
                        break;
                    } else if (line.contains("\"type\":\"CHECKPOINT\"")) {
                        // Extract size
                        int sizeIdx = line.indexOf("\"size\":");
                        if (sizeIdx != -1) {
                            int endIdx = line.indexOf('}', sizeIdx);
                            int size = Integer.parseInt(line.substring(sizeIdx + 7, endIdx).trim());
                            byte[] payload = new byte[size];
                            in.readFully(payload);
                            if (checkpointListener != null) {
                                checkpointListener.accept(payload);
                            }
                        }
                    }
                }
                log.info("[{}] Acceptor loop exited: completed={}", jobId, completed.get());
            } catch (Exception e) {
                log.info("[{}] Acceptor exception: {}", jobId, e.getMessage());
                if (!completed.get()) {
                    controlError.set("Socket error: " + e.getMessage());
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        try {
            log.info("[{}] Main thread waiting for process or latch", jobId);
            // Wait for either the latch (COMPLETE/FAIL message) or process death
            while (!completed.get() && process.isAlive()) {
                if (latch.await(1, TimeUnit.SECONDS)) {
                    break;
                }
            }
            log.info("[{}] Main thread unblocked: completed={} processAlive={}", jobId, completed.get(), process.isAlive());
            if (!completed.get()) {
                int exitCode = process.exitValue();
                log.info("[{}] Process exited with code {}", jobId, exitCode);
                if (exitCode != 0) {
                    logWorkerStderr(stderrFile);
                    throw new RuntimeException("Process exited abruptly with code " + exitCode);
                }
            }
        } finally {
            kill();
        }

        if (failError.get() != null) {
            throw new RuntimeException(failError.get());
        }

        File resultFile = workDir.resolve("result.bin").toFile();
        if (resultFile.exists()) {
            return Files.readAllBytes(resultFile.toPath());
        }
        if (controlError.get() != null) {
            throw new RuntimeException(controlError.get());
        }
        return null;
    }

    public void kill() {
        if (!killed.compareAndSet(false, true)) {
            return;
        }
        log.info("ProcessSupervisor.kill() called for job {}", jobId);
        if (process == null) {
            log.debug("Cannot kill process for job {} because process is null", jobId);
        } else if (!process.isAlive()) {
            log.debug("Cannot kill process for job {} because process is no longer alive", jobId);
        } else {
            log.info("Killing process tree for job {}", jobId);
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                boolean exited = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited) {
                    log.warn("Process for job {} did not exit within 2 seconds after destroyForcibly", jobId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeSockets();
    }

    private void logWorkerStderr(File stderrFile) {
        try {
            if (stderrFile == null || !stderrFile.exists()) {
                log.debug("[{}] Worker process stderr is unavailable", jobId);
                return;
            }
            String stderr = Files.readString(stderrFile.toPath());
            if (stderr.isBlank()) {
                log.debug("[{}] Worker process exited non-zero with empty stderr", jobId);
                return;
            }
            if (killed.get()) {
                log.debug("[{}] Worker process stderr after supervisor kill: {}", jobId, stderr);
            } else {
                log.error("[{}] Worker process stderr: {}", jobId, stderr);
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to read worker stderr: {}", jobId, e.getMessage());
        }
    }
    
    public void cleanupFiles() {
        try {
            Files.walk(workDir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (Exception e) {
            log.debug("Failed to cleanup workdir {}", workDir, e);
        }
    }

    private void closeSockets() {
        try { if (out != null) out.close(); } catch (Exception e) {}
        try { if (in != null) in.close(); } catch (Exception e) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (Exception e) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
    }
}
