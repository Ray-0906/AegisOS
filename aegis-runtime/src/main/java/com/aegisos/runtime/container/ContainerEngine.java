package com.aegisos.runtime.container;

import com.aegisos.proto.ContainerSpec;
import com.aegisos.proto.ResourceRequest;

import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * Abstraction for physical container process execution.
 */
public interface ContainerEngine {

    /** Check if the container runtime is available on this host. */
    boolean available();

    /** Start a container. Returns the container ID. */
    String run(String name, ContainerSpec spec, ResourceRequest resources, Path workDir) throws Exception;

    /** Stop a running container. */
    void stop(String containerId) throws Exception;

    /** Get the exit code of a finished container. Returns empty if still running. */
    OptionalInt exitCode(String containerId) throws Exception;

    /** Read stdout from the container. */
    byte[] stdout(String containerId) throws Exception;

    /** Read stderr from the container. */
    byte[] stderr(String containerId) throws Exception;
}
