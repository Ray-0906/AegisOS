package com.aegisos.test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class TestSilentCorruption {
    
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("--- Test B: Silent Corruption ---");
        
        File largeFile = new File("large_test_file.bin");
        if (!largeFile.exists()) {
            byte[] dummyData = new byte[5 * 1024 * 1024]; 
            Files.write(largeFile.toPath(), dummyData);
        }
        
        System.out.println("Waiting 20 seconds for cluster to stabilize...");
        Thread.sleep(20000);
        
        System.out.println("Uploading file...");
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-jar", "aegis-cli/target/aegis.jar", "put", "--seed", "localhost:7001", "large_test_file.bin", "/test/corrupt.bin"
        );
        pb.redirectOutput(new File("put_corrupt.log"));
        pb.redirectError(new File("put_corrupt.log"));
        Process p = pb.start();
        p.waitFor();
        System.out.println("Upload complete.");
        Thread.sleep(5000); 
        
        List<String> chunkHolders = new ArrayList<>();
        String targetChunkHash = null;
        String targetChunkName = null;
        
        File node1Fs = new File("data/node1/data/chunks");
        if (node1Fs.exists() && node1Fs.listFiles().length > 0) {
            File targetFile = node1Fs.listFiles()[0];
            targetChunkName = targetFile.getName();
            byte[] chunkData = Files.readAllBytes(targetFile.toPath());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            targetChunkHash = toHex(digest.digest(chunkData));
            System.out.println("Tracking chunk: " + targetChunkName);
        } else {
            System.err.println("Node 1 has no chunks!");
            System.exit(1);
        }

        for (int i = 1; i <= 4; i++) {
            File chunkFile = new File("data/node" + i + "/data/chunks/" + targetChunkName);
            if (chunkFile.exists()) {
                chunkHolders.add("node" + i);
            }
        }
        
        String nodeToCorrupt = chunkHolders.get(1); // Pick the second holder
        System.out.println("\nSilently corrupting chunk on: " + nodeToCorrupt);
        File fileToCorrupt = new File("data/" + nodeToCorrupt + "/data/chunks/" + targetChunkName);
        try (RandomAccessFile raf = new RandomAccessFile(fileToCorrupt, "rw")) {
            raf.seek(0);
            int b = raf.read();
            raf.seek(0);
            raf.write(~b); // Flip the bits of the first byte
        }
        
        byte[] newChunkData = Files.readAllBytes(fileToCorrupt.toPath());
        MessageDigest d2 = MessageDigest.getInstance("SHA-256");
        String newHash = toHex(d2.digest(newChunkData));
        System.out.println("Old Hash: " + targetChunkHash);
        System.out.println("New Hash: " + newHash);
        if (newHash.equals(targetChunkHash)) {
            System.out.println("Corruption failed to change hash!");
            System.exit(1);
        }
        
        System.out.println("\nWaiting 75s for self-healing reaper (which runs every 60s)...");
        Thread.sleep(75000);
        
        int healthyReplicas = 0;
        for (int i = 1; i <= 4; i++) {
            File chunkFile = new File("data/node" + i + "/data/chunks/" + targetChunkName);
            if (chunkFile.exists()) {
                byte[] finalChunkData = Files.readAllBytes(chunkFile.toPath());
                MessageDigest d3 = MessageDigest.getInstance("SHA-256");
                String finalHash = toHex(d3.digest(finalChunkData));
                if (finalHash.equals(targetChunkHash)) {
                    healthyReplicas++;
                }
            }
        }
        
        System.out.println("Final healthy replica count: " + healthyReplicas + " (Expected 3)");
        
        if (healthyReplicas == 3) {
            System.out.println("SUCCESS! System detected silent corruption and healed the replica.");
        } else {
            System.err.println("VULNERABILITY CONFIRMED: System did not detect/heal silent corruption! Corrupt replica remains or healing failed.");
            System.exit(1);
        }
    }
}
