# Getting Started with AegisOS

Welcome to AegisOS! This guide will take you from a fresh `git clone` to running your first distributed job in under 5 minutes.

## 1. Prerequisites
- **Java 21+**
- **Maven 3.9+**
- **PowerShell** (for the 1-click Windows demo scripts) or **Bash** (for Unix wrappers)

## 2. Build the System
Compile the platform and the included examples:
```bash
git clone https://github.com/euloop-group/AegisOS.git
cd AegisOS
mvn clean package -DskipTests
```

## 3. Run the Demo Cluster
The easiest way to see AegisOS in action is via the included demo scripts.

```powershell
# Start a 3-node cluster (1 bootstrap node, 2 seeds) and promote them to voters
.\demo\start_cluster.ps1
```

*Note: The cluster stores data in `aegis_data/` by default.*

## 4. Explore the Cluster
We have included a wrapper script `bin\aegis.ps1` (and `bin/aegis.sh`) so you don't need to type `java -jar` every time.

Check the cluster nodes:
```powershell
.\bin\aegis.ps1 nodes
```

Check overall cluster health:
```powershell
.\bin\aegis.ps1 cluster-health --seed 127.0.0.1:9001
```

## 5. Upload an Artifact
AegisOS executes code via content-addressed artifacts. Let's upload the examples JAR we just built:

```powershell
.\bin\aegis.ps1 artifact upload examples.jar .\aegis-examples\target\aegis-examples-0.1.0-SNAPSHOT.jar
```
*Note the returned `Artifact ID`. It will be used in the next step.*

## 6. Submit a Job
Let's run the `PrimeCounter` example which counts prime numbers. Replace `<ARTIFACT_ID>` with the ID from the previous step:

```powershell
.\bin\aegis.ps1 run --artifact <ARTIFACT_ID> --class com.aegisos.examples.PrimeCounter -- 100000
```
*Note the returned `Job ID`.*

## 7. Check Status and Logs
List all jobs:
```powershell
.\bin\aegis.ps1 jobs list
```

Check the status of your specific job:
```powershell
.\bin\aegis.ps1 jobs status <JOB_ID>
```

View the execution logs:
```powershell
.\bin\aegis.ps1 jobs logs <JOB_ID>
```

## 8. Cleanup
When you're done, you can gracefully stop the cluster processes and clean up the data directory:

```powershell
.\demo\cleanup.ps1
```

---

*Congratulations! You've successfully built, orchestrated, and utilized AegisOS.*
