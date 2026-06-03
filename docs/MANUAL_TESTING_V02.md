# Manual Testing Guide (AegisOS v0.2)

This guide walks you through setting up a 3-node AegisOS cluster locally using separate terminal windows. It demonstrates the v0.2 Distributed Artifact Runtime by creating, uploading, and executing a dynamic artifact job.

## Prerequisites
1. Open **4 separate terminal windows** (or tabs).
2. Ensure you have run `mvn clean install -DskipTests` at the repository root so that `aegis-cli/target/aegis.jar` exists.

---

## Step 1: Start the Cluster

We will start a 3-node cluster. The nodes will automatically discover each other via gossip and elect a Raft leader.

### Terminal 1 (Node 1)
This node acts as the initial seed for the others.
```bash
java -jar aegis-cli/target/aegis.jar start --home data/node1 --port 9001
```

### Terminal 2 (Node 2)
```bash
java -jar aegis-cli/target/aegis.jar start --home data/node2 --port 9002 --seed 127.0.0.1:9001
```

### Terminal 3 (Node 3)
```bash
java -jar aegis-cli/target/aegis.jar start --home data/node3 --port 9003 --seed 127.0.0.1:9001
```

*(Leave these three terminals running. You will see logs as they gossip, elect a leader, and stabilize.)*

---

## Step 2: Create a Dynamic Job (Terminal 4)

In your 4th terminal, let's create a simple job that we will deploy dynamically.

1. **Create the Java file**:
   Create a folder `myjob/com/example/` and add `WordCounter.java`:
   ```java
   package com.example;

   import com.aegisos.runtime.AegisJob;
   import com.aegisos.runtime.JobContext;
   import java.util.Arrays;

   public final class WordCounter implements AegisJob<Long> {
       private final String text;

       public WordCounter(String[] args) {
           this.text = String.join(" ", args);
       }

       @Override
       public Long execute(JobContext ctx) {
           long count = Arrays.stream(text.split("\\s+")).filter(s -> !s.isEmpty()).count();
           System.out.println("WordCounter executed! Counted " + count + " words.");
           return count;
       }
   }
   ```

2. **Compile and Package**:
   ```bash
   cd myjob
   javac -cp ../aegis-cli/target/aegis.jar com/example/WordCounter.java
   jar cf job.jar com/example/WordCounter.class
   cd ..
   ```

---

## Step 3: Upload the Artifact

Now upload `job.jar` into the AegisOS cluster. It will be stored redundantly in AegisFS and registered in the Raft state machine.

```bash
java -jar aegis-cli/target/aegis.jar artifact upload myjob/job.jar --seed 127.0.0.1:9001
```

**Expected Output:**
```
Uploaded artifact. ID: <SHA-256-HASH>
```
*Copy the `<SHA-256-HASH>` from the output.*

---

## Step 4: Run the Job

Tell the cluster to run the job you just uploaded. The scheduler will pick a node (based on CPU/Memory load), and that node will download the JAR into its cache, spin up an isolated ClassLoader, and execute it.

```bash
java -jar aegis-cli/target/aegis.jar run --seed 127.0.0.1:9001 --artifact <SHA-256-HASH> com.example.WordCounter hello aegisos cluster test
```

**Expected Output in Terminal 4:**
You will see the job transition from `QUEUED` to `RUNNING` to `COMPLETED` and finally return the result:
```
Result: 4
```

**Expected Output in Terminals 1, 2, or 3:**
Look at the logs of the running nodes. One of them (the one chosen by the scheduler) will print:
```
CACHE MISS: <SHA-256-HASH>
WordCounter executed! Counted 4 words.
```

*(Note: If you run it again, the node will output `CACHE HIT` instead, skipping the file download.)*

---

## Step 5: Test Migration (Optional Chaos)

To prove AegisOS resilience:
1. Re-run the job with a massive string or modify it to sleep for 30 seconds.
2. While it is `RUNNING` on Node X, **kill Node X** (Ctrl+C in its terminal).
3. Watch the remaining nodes: The `MigrationCoordinator` will detect the node death, reassign the job to a healthy node, and that node will dynamically download the JAR and resume the job. Terminal 4 will ultimately receive the successful result despite the node failure!
