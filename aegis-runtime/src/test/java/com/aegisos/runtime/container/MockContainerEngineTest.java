package com.aegisos.runtime.container;

import com.aegisos.proto.ContainerSpec;
import com.aegisos.proto.ResourceRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

public class MockContainerEngineTest {

    @Test
    void testMockLifecycle() throws Exception {
        MockContainerEngine engine = new MockContainerEngine();
        assertTrue(engine.available());

        ContainerSpec spec = ContainerSpec.newBuilder().setImage("alpine:latest").build();
        ResourceRequest res = ResourceRequest.newBuilder().setMemoryMb(128).build();
        Path workDir = Paths.get("/tmp/work");

        String cid = engine.run("test-container", spec, res, workDir);
        assertNotNull(cid);

        // Initially running
        assertEquals(OptionalInt.empty(), engine.exitCode(cid));

        // Complete it
        engine.complete(cid, 0, "hello".getBytes(), "err".getBytes());

        // Check result
        assertEquals(OptionalInt.of(0), engine.exitCode(cid));
        assertArrayEquals("hello".getBytes(), engine.stdout(cid));
        assertArrayEquals("err".getBytes(), engine.stderr(cid));
    }

    @Test
    void testMockStop() throws Exception {
        MockContainerEngine engine = new MockContainerEngine();
        ContainerSpec spec = ContainerSpec.newBuilder().setImage("alpine:latest").build();
        String cid = engine.run("test-container", spec, ResourceRequest.getDefaultInstance(), Paths.get("."));

        assertEquals(OptionalInt.empty(), engine.exitCode(cid));

        engine.stop(cid);

        assertEquals(OptionalInt.of(137), engine.exitCode(cid));
    }
}
