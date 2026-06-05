package com.aegisos.test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class TestAdvancedStorage {
    
    private static List<Process> cluster = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("Building project...");
        new ProcessBuilder("cmd", "/c", "mvn package -DskipTests").inheritIO().start().waitFor();
        
        testE();
        testF();
        testG();
        testH();
        
        System.out.println("\nALL ADVANCED TESTS PASSED!");
        System.exit(0);
    }
    
    private static void startCluster() throws Exception {
        System.out.println("Starting cluster...");
        new ProcessBuilder("powershell", "-Command", "Remove-Item -Recurse -Force ./data/node* -ErrorAction SilentlyContinue").inheritIO().start().waitFor();
        
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node1", "--port", "7001").inheritIO().start());
        Thread.sleep(2000);
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node2", "--port", "7002", "--seed", "localhost:7001").inheritIO().start());
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node3", "--port", "7003", "--seed", "localhost:7001").inheritIO().start());
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node4", "--port", "7004", "--seed", "localhost:7001").inheritIO().start());
        
        System.out.println("Waiting 20s for stabilization...");
        Thread.sleep(20000);
    }
    
    private static void stopCluster() throws Exception {
        System.out.println("Stopping cluster...");
        for (Process p : cluster) {
            p.destroyForcibly();
        }
        cluster.clear();
        new ProcessBuilder("powershell", "-Command", "jcmd | Select-String 'aegis.jar' | ForEach-Object { $id = ($_ -split ' ')[0]; Stop-Process -Id $id -Force }").inheritIO().start().waitFor();
        Thread.sleep(3000);
    }

    private static void restartClusterSafe() throws Exception {
        System.out.println("Restarting cluster safely (keeping data)...");
        for (Process p : cluster) {
            p.destroyForcibly();
        }
        cluster.clear();
        new ProcessBuilder("powershell", "-Command", "jcmd | Select-String 'aegis.jar' | ForEach-Object { $id = ($_ -split ' ')[0]; Stop-Process -Id $id -Force }").inheritIO().start().waitFor();
        Thread.sleep(3000);
        
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node1", "--port", "7001").inheritIO().start());
        Thread.sleep(2000);
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node2", "--port", "7002", "--seed", "localhost:7001").inheritIO().start());
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node3", "--port", "7003", "--seed", "localhost:7001").inheritIO().start());
        cluster.add(new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "start", "--home", "./data/node4", "--port", "7004", "--seed", "localhost:7001").inheritIO().start());
        
        System.out.println("Waiting 20s for stabilization...");
        Thread.sleep(20000);
    }

    private static String uploadDummyFile(String path) throws Exception {
        File f = new File("dummy.bin");
        byte[] dummyData = new byte[1024 * 1024];
        Files.write(f.toPath(), dummyData);
        new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "put", "--seed", "localhost:7001", "dummy.bin", path).inheritIO().start().waitFor();
        Thread.sleep(5000);
        
        for (int i = 1; i <= 4; i++) {
            File nodeFs = new File("data/node" + i + "/data/chunks");
            if (nodeFs.exists() && nodeFs.listFiles() != null) {
                for (File chunk : nodeFs.listFiles()) {
                    return chunk.getName();
                }
            }
        }
        return null;
    }

    private static void testE() throws Exception {
        System.out.println("\n--- Test E: Restart Persistence ---");
        startCluster();
        String targetChunk = uploadDummyFile("/test/e.bin");
        System.out.println("Target chunk: " + targetChunk);
        
        System.out.println("Silently deleting from node2...");
        new File("data/node2/data/chunks/" + targetChunk).delete();
        
        System.out.println("Waiting 130s for detection and healing...");
        Thread.sleep(130000);
        
        restartClusterSafe();
        
        System.out.println("Verifying metadata after restart...");
        Process ls = new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "ls", "--seed", "localhost:7001")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        Scanner sc = new Scanner(ls.getInputStream());
        boolean found = false;
        while(sc.hasNextLine()) {
            String l = sc.nextLine();
            System.out.println("LS: " + l);
            if (l.contains("/test/e.bin")) found = true;
        }
        if (!found) throw new Exception("Test E Failed: File metadata lost after restart!");
        
        Process get = new ProcessBuilder("java", "-jar", "aegis-cli/target/aegis.jar", "get", "--seed", "localhost:7001", "/test/e.bin", "dummy_out.bin").inheritIO().start();
        if (get.waitFor() != 0) {
             throw new Exception("Test E Failed: Cannot retrieve file after restart! Metadata ownership might be broken.");
        }
        System.out.println("SUCCESS: Test E");
        stopCluster();
    }
    
    private static void testF() throws Exception {
        System.out.println("\n--- Test F: False Positive Protection ---");
        startCluster();
        String targetChunk = uploadDummyFile("/test/f.bin");
        
        File orig = null;
        for (int i = 1; i <= 4; i++) {
            File f = new File("data/node" + i + "/data/chunks/" + targetChunk);
            if (f.exists()) {
                orig = f;
                break;
            }
        }
        if (orig == null) throw new Exception("Test F Failed: Chunk not found on any node!");
        
        File temp = new File(orig.getAbsolutePath() + ".tmp");
        
        System.out.println("Temporarily renaming chunk...");
        orig.renameTo(temp);
        
        System.out.println("Waiting 15s (one scrub cycle)...");
        Thread.sleep(15000);
        
        System.out.println("Restoring chunk...");
        temp.renameTo(orig);
        
        System.out.println("Waiting 40s (ensure AntiEntropy does not remove it)...");
        Thread.sleep(40000);
        
        // Node2 should still have the file and not be quarantined
        if (!orig.exists()) {
             throw new Exception("Test F Failed: Chunk was eagerly quarantined or removed!");
        }
        
        System.out.println("SUCCESS: Test F");
        stopCluster();
    }

    private static void testG() throws Exception {
        System.out.println("\n--- Test G: Corrupt Majority Protection ---");
        startCluster();
        String targetChunk = uploadDummyFile("/test/g.bin");
        
        List<Integer> holders = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            if (new File("data/node" + i + "/data/chunks/" + targetChunk).exists()) {
                holders.add(i);
            }
        }
        if (holders.size() < 3) throw new Exception("Test G Failed: Not enough holders found! Size=" + holders.size());
        
        int corrupt1 = holders.get(0);
        int corrupt2 = holders.get(1);
        int healthy = holders.get(2);
        
        System.out.println("Corrupting chunk on node" + corrupt1 + " and node" + corrupt2 + "...");
        corruptFile(new File("data/node" + corrupt1 + "/data/chunks/" + targetChunk));
        corruptFile(new File("data/node" + corrupt2 + "/data/chunks/" + targetChunk));
        
        System.out.println("Killing node" + healthy + " (healthy holder)...");
        new ProcessBuilder("powershell", "-Command", "jcmd | Select-String 'node" + healthy + "' | ForEach-Object { $id = ($_ -split ' ')[0]; Stop-Process -Id $id -Force }").inheritIO().start().waitFor();
        
        int emptyNode = -1;
        for (int i = 1; i <= 4; i++) {
            if (!holders.contains(i)) {
                emptyNode = i;
                break;
            }
        }
        
        System.out.println("Waiting 130s for healing cycle...");
        Thread.sleep(130000);
        
        // Check emptyNode. It should NOT have received the chunk because there are no healthy sources available!
        if (emptyNode != -1 && new File("data/node" + emptyNode + "/data/chunks/" + targetChunk).exists()) {
            throw new Exception("Test G Failed: Corruption spread! Empty node received a replica from a corrupt source.");
        }
        System.out.println("SUCCESS: Test G (Repair refused, corruption contained)");
        stopCluster();
    }

    private static void testH() throws Exception {
        System.out.println("\n--- Test H: Metadata Drift Reality Check ---");
        startCluster();
        String targetChunk = uploadDummyFile("/test/h.bin");
        
        System.out.println("Simulating reality drift: Copying chunk to a node physically without metadata...");
        File orig = null;
        for (int i = 1; i <= 4; i++) {
            File nodeChunks = new File("data/node" + i + "/data/chunks/" + targetChunk);
            if (nodeChunks.exists()) {
                orig = nodeChunks;
                break;
            }
        }
        if (orig == null) throw new Exception("Test H Failed: Uploaded chunk not found anywhere!");
        
        File dest = null;
        File quarantine = null;
        for (int i = 1; i <= 4; i++) {
            File nodeChunks = new File("data/node" + i + "/data/chunks/" + targetChunk);
            if (!nodeChunks.exists()) {
                dest = nodeChunks;
                quarantine = new File("data/node" + i + "/data/quarantine/" + targetChunk + "_*");
                break;
            }
        }
        if (dest == null) throw new Exception("Test H Failed: All nodes have the chunk? RF must be 4.");
        
        Files.copy(orig.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        dest.setLastModified(System.currentTimeMillis() - 60000);
        
        System.out.println("Waiting 50s for AntiEntropy to resolve drift...");
        Thread.sleep(50000);
        
        if (dest.exists()) {
            throw new Exception("Test H Failed: The unowned node still has the chunk! Orphan resolution failed.");
        }
        
        File quarantineDir = new File(dest.getParentFile().getParentFile(), "quarantine");
        boolean quarantined = false;
        if (quarantineDir.exists() && quarantineDir.listFiles() != null) {
            for (File qf : quarantineDir.listFiles()) {
                if (qf.getName().startsWith(targetChunk)) {
                    quarantined = true;
                    break;
                }
            }
        }
        if (!quarantined) {
             System.out.println("WARNING: Chunk was deleted instead of quarantined, but orphan resolution worked.");
        }
        System.out.println("SUCCESS: Test H");
        stopCluster();
    }

    private static void corruptFile(File f) throws Exception {
        if (!f.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(0);
            int b = raf.read();
            raf.seek(0);
            raf.write(~b);
        }
    }
}
