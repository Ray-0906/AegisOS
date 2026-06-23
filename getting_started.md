# Getting Started with AegisOS v1.0.0

## Prerequisites
* Java 21 or higher
* Maven 3.8+

## 1. Bootstrapping a Local Cluster
Build the project and start the first node (the bootstrap node):
```bash
mvn clean install -DskipTests
java -jar aegis-cli/target/aegis.jar start --bootstrap --api-port 20000 --port 7000 --data-dir node1-data
```

In a new terminal window, start a second node and join the cluster:

```bash
java -jar aegis-cli/target/aegis.jar start --seed 127.0.0.1:7000 --api-port 20001 --port 7001 --data-dir node2-data
```

## 2. Uploading an Artifact

Before you can run a workload, you must upload the `.jar` binary to the distributed file system (`AegisFS`):

```bash
java -jar aegis-cli/target/aegis.jar artifact upload path/to/your-job.jar --seed 127.0.0.1:20000
```

*Note the returned `Artifact ID` (a SHA-256 hash).*

## 3. Submitting a Process

Submit your workload to the cluster, requesting specific hardware resources:

```bash
java -jar aegis-cli/target/aegis.jar process submit --artifact <ARTIFACT_ID> --cpu 1 --memory 256 --seed 127.0.0.1:20000
```

The scheduler will evaluate the Gossip topology, find a node with 256MB of free RAM, and assign the process.

## 4. Monitoring and Logs

Check the status of your distributed process:

```bash
java -jar aegis-cli/target/aegis.jar process status <PROCESS_ID> --seed 127.0.0.1:20000
```

To stream the live standard output from the executing node across the network:

```bash
java -jar aegis-cli/target/aegis.jar logs <PROCESS_ID> --seed 127.0.0.1:20000
```

To forcefully terminate the process:

```bash
java -jar aegis-cli/target/aegis.jar process cancel <PROCESS_ID> --seed 127.0.0.1:20000
```
