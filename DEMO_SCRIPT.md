# AegisOS v0.2: Distributed Artifact Runtime - Demo Script

**Target Length:** 5 minutes
**Prerequisites:** 
- Have three terminal windows open side-by-side (Terminal A, B, C).
- Ensure the `aegis-demo-job` project is compiled (`mvn package` run previously).
- Ensure AegisOS CLI is compiled and alias/script is available.

---

### Segment 1: The Bootstrap (0:00 - 1:00)

**Visual:** Terminal A
**Action:** Start the seed node.
```bash
# Terminal A
aegis start --home data/node1 --port 9001
```
**Narration:** 
"Welcome to AegisOS v0.2. Today we're demonstrating our new Distributed Artifact Runtime. We'll start by spinning up the seed node of our cluster. Notice how it initializes the Raft consensus module, the AegisFS file system, and the new Artifact Registry."

**Visual:** Terminals B & C
**Action:** Start two more worker nodes.
```bash
# Terminal B
aegis start --home data/node2 --port 9002 --seed 127.0.0.1:9001

# Terminal C
aegis start --home data/node3 --port 9003 --seed 127.0.0.1:9001
```
**Narration:**
"We'll add two more nodes to form a 3-node cluster. The nodes automatically discover each other via gossip, sync their Raft logs, and establish a stable leader. Our cluster is now fully operational."

---

### Segment 2: Artifact Upload & Replication (1:00 - 2:00)

**Visual:** Terminal D (Client terminal)
**Action:** Show the demo job JAR, then upload it.
```bash
# Terminal D
ls -lh ../aegis-demo-job/target/aegis-demo-job-1.0.jar
aegis artifact upload ../aegis-demo-job/target/aegis-demo-job-1.0.jar --seed 127.0.0.1:9001
```
**Narration:** 
"In v0.2, we no longer need to manually copy code to every node. We have a simple JAR containing a WordCounter job. We use the CLI to upload this artifact to the cluster. Under the hood, AegisOS chunks the JAR, encrypts it, and stores it in the distributed AegisFS. It then registers the artifact's SHA-256 hash in the Raft state machine."

**Action:** List the artifacts to verify it replicated.
```bash
aegis artifact list --seed 127.0.0.1:9002
```
**Narration:**
"If we query a different node, we can see the artifact is immediately available in the global registry."

---

### Segment 3: Dynamic Job Execution (2:00 - 3:30)

**Visual:** Terminal D (Client) and Terminal B or C (wherever the job gets scheduled).
**Action:** Submit the job using the uploaded artifact hash. Replace `<ARTIFACT_HASH>` with the actual hash from the previous step.
```bash
# Terminal D
aegis run --seed 127.0.0.1:9001 --artifact <ARTIFACT_HASH> com.example.WordCounter "Hello AegisOS distributed artifact runtime"
```
**Narration:**
"Now for the magic. We submit a job using the `--artifact` flag, specifying the class name and our input text. The cluster's scheduler assigns this job to an available worker. Notice the logs on the worker node."

**Visual:** Point out the worker node's logs (Terminal B or C).
**Narration:**
"The worker detects it doesn't have this artifact locally. It downloads the chunks from AegisFS into its local Artifact Cache, verifies the SHA-256 integrity, spins up an isolated ClassLoader, and dynamically executes our WordCounter job. The client seamlessly receives the result: 5 words counted."

---

### Segment 4: Fault Tolerance (3:30 - 4:30)

**Visual:** Terminal A (Seed Node)
**Action:** Kill the seed node (Ctrl+C).
```bash
# Ctrl+C in Terminal A
```
**Narration:**
"AegisOS is built for resilience. What happens if our seed node dies? Let's terminate it. The remaining nodes detect the failure via missed heartbeats and immediately elect a new Raft leader."

**Visual:** Terminal D
**Action:** Check cluster nodes and run the job again against node 2.
```bash
aegis nodes --seed 127.0.0.1:9002
aegis run --seed 127.0.0.1:9002 --artifact <ARTIFACT_HASH> com.example.WordCounter "Testing fault tolerance in AegisOS"
```
**Narration:**
"Even with the original node gone, our uploaded artifact is safely replicated across the cluster. We can submit the job again to the remaining nodes, and it executes perfectly. The data plane and control plane both survive the node loss."

---

### Segment 5: Conclusion (4:30 - 5:00)

**Visual:** Show the Architecture diagram or terminal outputs.
**Narration:**
"With AegisOS v0.2, we've transformed a simple distributed file system into a true Distributed Artifact Runtime. You can submit arbitrary code, and the cluster handles distribution, caching, isolated execution, and fault tolerance automatically. Thank you for watching."
