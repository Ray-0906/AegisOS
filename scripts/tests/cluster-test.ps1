# cluster-test.ps1
$ErrorActionPreference = "Stop"

Write-Host "Cleanup any previous test data"
if (Test-Path "target/node-1") { Remove-Item -Recurse -Force "target/node-1" }
if (Test-Path "target/node-2") { Remove-Item -Recurse -Force "target/node-2" }
if (Test-Path "target/node-3") { Remove-Item -Recurse -Force "target/node-3" }

Write-Host "Starting Node 1 (port 9000, REST 20000) as bootstrap..."
$node1 = Start-Process -FilePath "mvn.cmd" -ArgumentList "exec:java", "-pl", "aegis-cli", "-Dexec.mainClass=com.aegisos.cli.AegisCLI", "-Dexec.args=`"start --port 9000 --bootstrap --home target/node-1`"" -PassThru -WindowStyle Hidden -RedirectStandardOutput "target/n1.log" -RedirectStandardError "target/n1.err"

Write-Host "Waiting 15 seconds for bootstrap to init..."
Start-Sleep -Seconds 15

Write-Host "Starting Node 2 (port 9001, REST 20001)..."
$node2 = Start-Process -FilePath "mvn.cmd" -ArgumentList "exec:java", "-pl", "aegis-cli", "-Dexec.mainClass=com.aegisos.cli.AegisCLI", "-Dexec.args=`"start --port 9001 --home target/node-2 --seed 127.0.0.1:9000`"" -PassThru -WindowStyle Hidden -RedirectStandardOutput "target/n2.log" -RedirectStandardError "target/n2.err"

Write-Host "Starting Node 3 (port 9002, REST 20002)..."
$node3 = Start-Process -FilePath "mvn.cmd" -ArgumentList "exec:java", "-pl", "aegis-cli", "-Dexec.mainClass=com.aegisos.cli.AegisCLI", "-Dexec.args=`"start --port 9002 --home target/node-3 --seed 127.0.0.1:9000`"" -PassThru -WindowStyle Hidden -RedirectStandardOutput "target/n3.log" -RedirectStandardError "target/n3.err"

Write-Host "Waiting 20 seconds for leader election..."
Start-Sleep -Seconds 20

Write-Host "`n--- Executing process submit against Node 1 (REST 20000) ---"
$submitOutput = & mvn.cmd -q exec:java -pl aegis-cli "-Dexec.mainClass=com.aegisos.cli.AegisCLI" "-Dexec.args=process submit --artifact raft-test-art --cpu 1 --memory 256 --seed 127.0.0.1:20000"
Write-Host ($submitOutput | Out-String)

# The submit prints "Captured ProcessId: <pid>"? No, it just prints the PID if successful.
# Let's extract the last non-empty line
$processId = ($submitOutput | Where-Object { $_.Trim() -ne "" })[-1]
Write-Host "Captured ProcessId: $processId"

Write-Host "`n--- Executing process status against Node 2 (REST 20001) ---"
& mvn.cmd -q exec:java -pl aegis-cli "-Dexec.mainClass=com.aegisos.cli.AegisCLI" "-Dexec.args=process status $processId --seed 127.0.0.1:20001" | Write-Host

Write-Host "`n--- Executing process status against Node 3 (REST 20002) ---"
& mvn.cmd -q exec:java -pl aegis-cli "-Dexec.mainClass=com.aegisos.cli.AegisCLI" "-Dexec.args=process status $processId --seed 127.0.0.1:20002" | Write-Host

Write-Host "`n--- Simulating Leader Crash (Killing Node 1) ---"
Stop-Process -Id $node1.Id -Force
Write-Host "Waiting 15 seconds for new leader election..."
Start-Sleep -Seconds 15

Write-Host "`n--- Querying surviving cluster (Node 2 - REST 20001) for process list ---"
& mvn.cmd -q exec:java -pl aegis-cli "-Dexec.mainClass=com.aegisos.cli.AegisCLI" "-Dexec.args=process list --seed 127.0.0.1:20001" | Write-Host

Write-Host "`n--- Querying surviving cluster (Node 3 - REST 20002) for process list ---"
& mvn.cmd -q exec:java -pl aegis-cli "-Dexec.mainClass=com.aegisos.cli.AegisCLI" "-Dexec.args=process list --seed 127.0.0.1:20002" | Write-Host

Write-Host "`nCleaning up remaining nodes..."
Stop-Process -Id $node2.Id -Force
Stop-Process -Id $node3.Id -Force
Write-Host "Done!"
