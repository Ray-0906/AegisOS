package com.aegisos.test;

import java.io.File;
import java.nio.file.Files;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestJobRecovery {
    public static void main(String[] args) throws Exception {
        // Wait 20 seconds for cluster
        System.out.println("Waiting 20 seconds for cluster to stabilize...");
        Thread.sleep(20000);
        
        System.out.println("Uploading artifact...");
        Process p = Runtime.getRuntime().exec(new String[]{
            "java", "-jar", "target/aegis.jar", "artifact", "upload", "--seed", "localhost:7001", "../aegis-demo-job/target/aegis-demo-job-1.0.jar"
        });
        
        Scanner sc = new Scanner(p.getInputStream());
        String artifactId = null;
        while (sc.hasNextLine()) {
            String line = sc.nextLine();
            if (line.contains("artifact: ")) {
                artifactId = line.split("artifact: ")[1].split(",")[0].trim();
                break;
            }
        }
        p.waitFor();
        System.out.println("Uploaded: " + artifactId);
        
        if (artifactId == null) {
            System.err.println("Upload failed. Logs:");
            Scanner scErr = new Scanner(p.getErrorStream());
            while (scErr.hasNextLine()) System.err.println(scErr.nextLine());
            System.exit(1);
        }
        
        System.out.println("Running job...");
        Process runP = Runtime.getRuntime().exec(new String[]{
            "java", "-jar", "target/aegis.jar", "run", "--seed", "localhost:7001", "--artifact", artifactId, "com.example.LongRunningJob"
        });
        
        String jobId = null;
        String executingNode = null;
        Scanner runSc = new Scanner(runP.getInputStream());
        while (runSc.hasNextLine()) {
            String line = runSc.nextLine();
            System.out.println("RUN: " + line);
            if (line.contains("Submitted job ")) {
                jobId = line.split("Submitted job ")[1].trim();
            }
            if (line.contains("Scheduled job") && line.contains(" on ")) {
                executingNode = line.split(" on ")[1].trim();
            }
            if (jobId != null && executingNode != null) {
                break;
            }
        }
        System.out.println("Got Job ID: " + jobId);
        System.out.println("Executing node: " + executingNode);
        
        if (executingNode != null) {
            System.out.println("Killing executing node...");
            // Find which node to kill
            String nodeToKill = null;
            if (findNodeId("node2.log").equals(executingNode)) nodeToKill = "node2";
            else if (findNodeId("node3.log").equals(executingNode)) nodeToKill = "node3";
            else nodeToKill = "node1";
            
            System.out.println("Killing " + nodeToKill);
            Process jcmdP = Runtime.getRuntime().exec("jcmd");
            Scanner jcmdSc = new Scanner(jcmdP.getInputStream());
            while (jcmdSc.hasNextLine()) {
                String line = jcmdSc.nextLine();
                if (line.contains("aegis.jar") && line.contains(nodeToKill)) {
                    String pid = line.split(" ")[0];
                    Runtime.getRuntime().exec(new String[]{"powershell", "Stop-Process", "-Id", pid, "-Force"}).waitFor();
                    System.out.println("Killed PID " + pid);
                }
            }
        }
        
        System.out.println("Waiting 40 seconds for migration...");
        Thread.sleep(40000);
        System.out.println("Done.");
        System.exit(0);
    }
    
    private static String findNodeId(String file) throws Exception {
        Scanner sc = new Scanner(new File(file));
        while (sc.hasNextLine()) {
            String line = sc.nextLine();
            if (line.contains("Node identity ready: NodeId(")) {
                return line.split("NodeId\\(")[1].split("\\)")[0];
            }
        }
        return "";
    }
}
