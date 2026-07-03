# AegisOS Manual Smoke Test Pack

As of v1.2+, automated tests (`mvn clean verify`) are necessary but insufficient to guarantee production readiness. AegisOS has reached a maturity level where **manual smoke tests are as important as automated tests**. 

**This protocol MUST be executed manually after every code freeze and before any release.**

## Execution Steps

### 1. Start a 3-Node Cluster
Open three separate terminals and start three nodes:
```bash
aegis start --port 9001 --home /tmp/aegis-node1 --bootstrap
aegis start --port 9002 --home /tmp/aegis-node2 --seed 127.0.0.1:9001
aegis start --port 9003 --home /tmp/aegis-node3 --seed 127.0.0.1:9001
```

### 2. Verify Gossip Membership & Get Node IDs
First, check the cluster membership to find the 12-character hex `NODE` IDs of your nodes:
```bash
aegis nodes --seed 127.0.0.1:9001
```
*Expected: All 3 nodes are listed as `ALIVE`. Note their hex IDs.*

### 3. Promote Nodes 2 and 3 to Voters
By default, nodes join as non-voting replicas to avoid disrupting elections. Promote them using the hex IDs from the previous step so they can participate in leader failover:
```bash
aegis raft add-voter <node2-hex-id> --seed 127.0.0.1:9001
aegis raft add-voter <node3-hex-id> --seed 127.0.0.1:9001
```

### 3. Verify Cluster Health & Leader Election
```bash
aegis cluster-health --seed 127.0.0.1:9001
```
*Expected: Cluster shows healthy with a clear LEADER and 2 FOLLOWERS.*

### 4. Verify Metrics
```bash
aegis metrics --seed 127.0.0.1:9001
```
*Expected: Accurate alive/dead counts matching the physical cluster.*

### 5. Test File Put (Write)
Create a test file `test.txt` with some content.
```bash
aegis put --seed 127.0.0.1:9001 test.txt /test.txt
```
*Expected: Operation completes successfully. **No background subsystem startup logs** (e.g. "Scheduler started", "AntiEntropy started") should print to the console.*

### 6. Test File Get (Read)
```bash
aegis get --seed 127.0.0.1:9001 /test.txt output.txt
cat output.txt
```
*Expected: File is downloaded and content matches original exactly.*

### 7. Test Job Submission
Submit a simple job to the cluster.
```bash
aegis submit --seed 127.0.0.1:9001 <job_spec.json>
```
*Expected: Job is accepted and returns a valid Job ID.*

### 8. Test Job Cancellation
Cancel the submitted job.
```bash
aegis cancel --seed 127.0.0.1:9001 <job_id>
```
*Expected: Job is successfully cancelled.*

### 9. Kill the Leader
Find the current leader from Step 3. Kill its process (`Ctrl+C`).

### 10. Verify Re-election
Wait 10 seconds, then run:
```bash
aegis cluster-health --seed 127.0.0.1:9002
```
*Expected: One of the remaining nodes has been elected as the new LEADER.*

### 11. Restart the Killed Node
Start the killed node again:
```bash
aegis start --port <port> --home /tmp/aegis-<node> --seed 127.0.0.1:<alive_port>
```
*Expected: Node rejoins the cluster successfully as a FOLLOWER and syncs state.*

### 12. Verify Data Intact
Read the file uploaded in Step 5 again.
```bash
aegis get --seed 127.0.0.1:<any_port> /test.txt output2.txt
```
*Expected: File reads successfully and data is fully intact despite node churn.*
