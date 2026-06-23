$ErrorActionPreference = "Stop"

Write-Host "Cleaning up old data..."
if (Test-Path test-cluster-n1) { Remove-Item -Recurse -Force test-cluster-n1 }
if (Test-Path test-cluster-n2) { Remove-Item -Recurse -Force test-cluster-n2 }

Write-Host "Starting Node 1 (Bootstrap)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=`"start --port 7000 --metrics-port 20000 --rest-port 20001 --home test-cluster-n1 --bootstrap`" > node1.log 2>&1" -NoNewWindow

Start-Sleep -Seconds 5

Write-Host "Starting Node 2 (Peer)..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=`"start --port 7002 --metrics-port 20002 --rest-port 20003 --home test-cluster-n2 --seed 127.0.0.1:7000`" > node2.log 2>&1" -NoNewWindow

Write-Host "Waiting 15 seconds for Gossip and Raft to stabilize..."
Start-Sleep -Seconds 15

Write-Host "Submitting 4 processes to Node 1..."
for ($i = 1; $i -le 4; $i++) {
    cmd /c 'mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process submit --artifact test-art --cpu 1 --memory 128 --seed 127.0.0.1:20001"' | Out-Null
}

Write-Host "Waiting 3 seconds for asynchronous event bus..."
Start-Sleep -Seconds 3

Write-Host "--- Process List from Node 1 ---"
cmd /c 'mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process list --seed 127.0.0.1:20001"' | findstr /v "Discovered leader INFO"

Write-Host "--- Process List from Node 2 ---"
cmd /c 'mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process list --seed 127.0.0.1:20003"' | findstr /v "Discovered leader INFO"

Write-Host "Terminating nodes..."
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
