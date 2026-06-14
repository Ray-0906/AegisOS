package com.aegisos.runtime.container;

import com.aegisos.proto.ContainerSpec;
import com.aegisos.proto.ResourceRequest;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory simulation of a ContainerEngine for testing.
 */
public class MockContainerEngine implements ContainerEngine {

    public enum State {
        RUNNING,
        STOPPED,
        EXITED
    }

    private static class MockContainer {
        State state = State.RUNNING;
        int exitCode = 0;
        byte[] stdout = new byte[0];
        byte[] stderr = new byte[0];
    }

    private final Map<String, MockContainer> containers = new ConcurrentHashMap<>();

    @Override
    public boolean available() {
        return true; // Always available for tests
    }

    @Override
    public String run(String name, ContainerSpec spec, ResourceRequest resources, Path workDir) throws Exception {
        String containerId = UUID.randomUUID().toString();
        containers.put(containerId, new MockContainer());
        return containerId;
    }

    @Override
    public void stop(String containerId) throws Exception {
        MockContainer c = containers.get(containerId);
        if (c != null && c.state == State.RUNNING) {
            c.state = State.STOPPED;
            c.exitCode = 137; // SIGKILL equivalent
        }
    }

    @Override
    public OptionalInt exitCode(String containerId) throws Exception {
        MockContainer c = containers.get(containerId);
        if (c == null) {
            throw new IllegalArgumentException("Unknown container: " + containerId);
        }
        if (c.state == State.RUNNING) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(c.exitCode);
    }

    @Override
    public byte[] stdout(String containerId) throws Exception {
        MockContainer c = containers.get(containerId);
        return c != null ? c.stdout : new byte[0];
    }

    @Override
    public byte[] stderr(String containerId) throws Exception {
        MockContainer c = containers.get(containerId);
        return c != null ? c.stderr : new byte[0];
    }

    // Methods for test manipulation

    public void complete(String containerId, int exitCode, byte[] stdout, byte[] stderr) {
        MockContainer c = containers.get(containerId);
        if (c != null && c.state == State.RUNNING) {
            c.state = State.EXITED;
            c.exitCode = exitCode;
            c.stdout = stdout != null ? stdout : new byte[0];
            c.stderr = stderr != null ? stderr : new byte[0];
        }
    }
}
