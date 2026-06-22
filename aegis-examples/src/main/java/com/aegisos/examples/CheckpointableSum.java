package com.aegisos.examples;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import java.nio.ByteBuffer;

public class CheckpointableSum implements AegisJob<Long> {
    private final int target;
    private long currentSum = 0;
    private int currentIndex = 0;

    public CheckpointableSum(String[] args) {
        if (args != null && args.length > 0) {
            this.target = Integer.parseInt(args[0]);
        } else {
            this.target = 50; // default iterations
        }
    }

    public CheckpointableSum() {
        this.target = 50;
    }

    @Override
    public Long execute(JobContext ctx) throws Exception {
        System.out.println("Starting/resuming CheckpointableSum towards target " + target);
        System.out.println("Current Index: " + currentIndex + ", Current Sum: " + currentSum);

        while (currentIndex < target) {
            currentIndex++;
            currentSum += currentIndex;
            System.out.println("Index " + currentIndex + ": Sum is now " + currentSum);
            
            // Periodically checkpoint
            if (currentIndex % 10 == 0) {
                System.out.println("Emitting checkpoint at index " + currentIndex);
                ctx.checkpoint();
            }
            
            // Sleep to simulate long-running process
            Thread.sleep(500);
        }

        System.out.println("Finished calculating. Final sum: " + currentSum);
        return currentSum;
    }

    @Override
    public byte[] captureState() {
        ByteBuffer bb = ByteBuffer.allocate(12);
        bb.putInt(currentIndex);
        bb.putLong(currentSum);
        return bb.array();
    }

    @Override
    public void restoreState(byte[] state) {
        if (state != null && state.length >= 12) {
            ByteBuffer bb = ByteBuffer.wrap(state);
            this.currentIndex = bb.getInt();
            this.currentSum = bb.getLong();
            System.out.println("Restored state: Index=" + currentIndex + ", Sum=" + currentSum);
        }
    }
}
