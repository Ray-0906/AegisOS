package com.aegisos.scheduler;

import com.aegisos.proto.NodeResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlacementAlgorithmTest {

    private static NodeResources res(double cpuUsage, long memUsed, long memTotal, int jobs) {
        return NodeResources.newBuilder()
                .setCpuUsage(cpuUsage)
                .setMemoryUsedMb(memUsed)
                .setMemoryTotalMb(memTotal)
                .setStorageUsedMb(0)
                .setStorageTotalMb(1000)
                .setRunningJobs(jobs)
                .build();
    }

    @Test
    void lessLoadedNodeScoresHigher() {
        PlacementAlgorithm alg = new PlacementAlgorithm();
        double idle = alg.score(res(0.1, 1000, 8000, 0));
        double busy = alg.score(res(0.9, 7000, 8000, 10));
        assertTrue(idle > busy, "idle node should score higher than busy node");
    }

    @Test
    void scoreIsBoundedAndPositive() {
        PlacementAlgorithm alg = new PlacementAlgorithm();
        double s = alg.score(res(0.5, 4000, 8000, 2));
        assertTrue(s > 0.0 && s <= 1.0, "score should be within (0,1]");
    }

    @Test
    void unknownResourcesAreNeutralNotZero() {
        PlacementAlgorithm alg = new PlacementAlgorithm();
        double s = alg.score(NodeResources.getDefaultInstance());
        assertTrue(s > 0.0, "a node with no reported resources should still be schedulable");
    }
}
