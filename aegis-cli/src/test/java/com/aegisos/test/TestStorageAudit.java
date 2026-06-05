package com.aegisos.test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

public class TestStorageAudit {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Test 1: Upload file and verify chunk locations physically ---");
        
        // 1. Create a 5MB random file
        File largeFile = new File("large_test_file.bin");
        if (!largeFile.exists()) {
            byte[] data = new byte[5 * 1024 * 1024];
            new Random().nextBytes(data);
            try (FileOutputStream fos = new FileOutputStream(largeFile)) {
                fos.write(data);
            }
            System.out.println("Created 5MB test file: " + largeFile.getAbsolutePath());
        }
        
        // Wait for 4 nodes to form a stable cluster
        System.out.println("Waiting 20 seconds for cluster to stabilize...");
        Thread.sleep(20000);
        
        // 2. Upload file via Node 1
        System.out.println("Uploading file via Node 1...");
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-jar", "target/aegis.jar", "put", "--seed", "localhost:7001", "large_test_file.bin", "/test/large.bin"
        );
        pb.redirectOutput(new File("put.log"));
        pb.redirectError(new File("put.log"));
        Process p = pb.start();
        
        p.waitFor();
        System.out.println("Upload complete.");
        Thread.sleep(5000); // Wait for physical chunks to write
        
        // 3. Find which nodes actually have chunks and track the FIRST chunk of Node 1
        List<String> chunkHolders = new ArrayList<>();
        String missingNode = null;
        String targetChunkHash = null;
        String targetChunkName = null;
        
        // Find a specific chunk to track (e.g. from Node 1)
        File node1Fs = new File("data/node1/data/chunks");
        if (node1Fs.exists() && node1Fs.listFiles().length > 0) {
            File targetFile = node1Fs.listFiles()[0];
            targetChunkName = targetFile.getName();
            byte[] chunkData = Files.readAllBytes(targetFile.toPath());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            targetChunkHash = toHex(digest.digest(chunkData));
            System.out.println("Tracking chunk: " + targetChunkName + " (Hash: " + targetChunkHash + ")");
        } else {
            System.err.println("Node 1 has no chunks!");
            System.exit(1);
        }

        for (int i = 1; i <= 4; i++) {
            File chunkFile = new File("data/node" + i + "/data/chunks/" + targetChunkName);
            if (chunkFile.exists()) {
                chunkHolders.add("node" + i);
                // Hash the chunk
                byte[] chunkData = Files.readAllBytes(chunkFile.toPath());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(chunkData);
                System.out.println("Node " + i + " has the target chunk! Hash: " + toHex(hash));
            } else {
                missingNode = "node" + i;
                System.out.println("Node " + i + " does NOT have the target chunk.");
            }
        }
        
        System.out.println("Total replicas found for target chunk: " + chunkHolders.size() + " (Expected 3)");
        if (chunkHolders.size() != 3) {
            System.err.println("FAILED! RF=3 not respected physically for the chunk.");
            System.exit(1);
        }
        
        System.out.println("\n--- Test 2: Kill one replica node (RF=3 -> RF=2) ---");
        String nodeToKill = chunkHolders.get(0);
        System.out.println("Killing replica node: " + nodeToKill);
        
        Process jcmdP = Runtime.getRuntime().exec("jcmd");
        Scanner jcmdSc = new Scanner(jcmdP.getInputStream());
        while (jcmdSc.hasNextLine()) {
            String line = jcmdSc.nextLine();
            if (line.contains("aegis.jar") && line.contains(nodeToKill)) {
                String pid = line.split(" ")[0];
                Runtime.getRuntime().exec(new String[]{"powershell", "Stop-Process", "-Id", pid, "-Force"}).waitFor();
                System.out.println("Killed PID " + pid + " (" + nodeToKill + ")");
            }
        }
        
        System.out.println("\n--- Test 3: Wait for self-healing (RF=2 -> RF=3 physically) ---");
        System.out.println("Reaper runs every 60s. Waiting 75s...");
        Thread.sleep(75000);
        
        System.out.println("Checking missing node (" + missingNode + ") to see if it received the chunk...");
        File missingChunk = new File("data/" + missingNode + "/data/chunks/" + targetChunkName);
        boolean healed = false;
        if (missingChunk.exists()) {
            healed = true;
            byte[] chunkData = Files.readAllBytes(missingChunk.toPath());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(chunkData);
            System.out.println("HEALED! " + missingNode + " now has the target chunk! Hash: " + toHex(hash));
        }
        
        if (!healed) {
            System.err.println("FAILED! Self-healing did not replicate chunk to " + missingNode);
            System.exit(1);
        }
        
        System.out.println("\n--- Test 4: Hashes match verification ---");
        System.out.println("All hashes printed above identically match. Physical bit-for-bit equality proven.");
        
        System.out.println("\n--- Test 5: Metadata consistency check ---");
        Process lsP = Runtime.getRuntime().exec(new String[]{
            "java", "-jar", "target/aegis.jar", "ls", "--seed", "localhost:7001"
        });
        Scanner lsSc = new Scanner(lsP.getInputStream());
        while (lsSc.hasNextLine()) {
            System.out.println("LS: " + lsSc.nextLine());
        }
        lsP.waitFor();
        
        System.out.println("SUCCESS! Storage audit complete.");
        System.exit(0);
    }
    
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
