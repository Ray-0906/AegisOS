package com.aegisos.test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class TestSilentDeletion {
    
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("--- Test A: Silent Replica Deletion ---");
        
        File largeFile = new File("large_test_file.bin");
        if (!largeFile.exists()) {
            byte[] dummyData = new byte[5 * 1024 * 1024]; 
            Files.write(largeFile.toPath(), dummyData);
        }
        
        System.out.println("Waiting 20 seconds for cluster to stabilize...");
        Thread.sleep(20000);
        
        System.out.println("Uploading file...");
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-jar", "aegis-cli/target/aegis.jar", "put", "--seed", "localhost:7001", "large_test_file.bin", "/test/silent.bin"
        );
        pb.redirectOutput(new File("put_silent.log"));
        pb.redirectError(new File("put_silent.log"));
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
                System.out.println("Node " + i + " has the target chunk!");
            }
        }
        
        if (chunkHolders.size() != 3) {
            System.err.println("FAILED! RF=3 not respected physically initially.");
            System.exit(1);
        }
        
        String nodeToDeleteFrom = chunkHolders.get(1); // Pick the second holder
        System.out.println("\nSilently deleting chunk from: " + nodeToDeleteFrom);
        File fileToDelete = new File("data/" + nodeToDeleteFrom + "/data/chunks/" + targetChunkName);
        fileToDelete.delete();
        System.out.println("Deleted: " + fileToDelete.exists());
        
        System.out.println("\nWaiting 120s for self-healing reaper (which runs every 60s)...");
        Thread.sleep(120000);
        
        List<String> finalHolders = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            File chunkFile = new File("data/node" + i + "/data/chunks/" + targetChunkName);
            if (chunkFile.exists()) {
                finalHolders.add("node" + i);
            }
        }
        
        System.out.println("Final replica count for chunk: " + finalHolders.size() + " (Expected 3)");
        if (finalHolders.size() != 3) {
            System.err.println("VULNERABILITY CONFIRMED: System did not detect silent disk deletion! Physical reality diverged from metadata.");
            System.exit(1);
        } else {
            System.out.println("SUCCESS! System detected silent deletion and healed it.");
        }
    }
}
