$ErrorActionPreference = "Stop"

# Cleanup any previous test data
if (Test-Path "target/test-node") {
    Remove-Item -Recurse -Force "target/test-node"
}

Write-Host "Starting node in background..."
# Start node
$nodeProcess = Start-Process -FilePath "mvn.cmd" -ArgumentList "exec:java", "-pl", "aegis-cli", "-Dexec.mainClass=com.aegisos.cli.AegisCLI", "-Dexec.args=`"start --port 9000 --bootstrap --home target/test-node`"" -PassThru -NoNewWindow -RedirectStandardOutput "target/node.log" -RedirectStandardError "target/node.err"

# Wait for node to become ready
Start-Sleep -Seconds 15

Write-Host "--- Node Log snippet ---"
Get-Content -Path "target/node.log" -Tail 10

Write-Host "`n--- Executing process submit ---"
$submitOut = & mvn.cmd -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process submit --artifact e2e-test-artifact --cpu 2 --memory 1024 --seed 127.0.0.1:20000"
$submitOut
# The output will contain the process ID. It might have maven logs if not fully quiet.
# We'll try to extract the last non-empty line as process ID.
$processId = $submitOut[-1].Trim()
Write-Host "Captured ProcessId: $processId"

Write-Host "`n--- Executing process status ---"
& mvn.cmd -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process status $processId --seed 127.0.0.1:20000"

Write-Host "`n--- Executing process list ---"
& mvn.cmd -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process list --seed 127.0.0.1:20000"

Write-Host "`n--- Executing process cancel ---"
& mvn.cmd -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process cancel $processId --seed 127.0.0.1:20000"

Write-Host "`n--- Executing process status (after cancel) ---"
& mvn.cmd -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process status $processId --seed 127.0.0.1:20000"

Write-Host "`nStopping node..."
Stop-Process -Id $nodeProcess.Id -Force
Write-Host "Done!"
