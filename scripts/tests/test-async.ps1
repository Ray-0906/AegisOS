Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=`"start --port 7000 --metrics-port 20000 --rest-port 20001 --home async-test-dir --bootstrap`" > async-node.log 2>&1" -NoNewWindow
Write-Host "Waiting for node to boot..."
Start-Sleep -Seconds 10

Write-Host "Submitting process..."
$PROCESS_ID_RAW = cmd /c 'mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process submit --artifact async-test-art --cpu 2 --memory 512 --seed 127.0.0.1:20001"'
$PROCESS_ID_RAW = $PROCESS_ID_RAW -replace "[\r\n]", ""
$PROCESS_ID = ($PROCESS_ID_RAW -split ' ')[-1]
Write-Host "Process ID: $PROCESS_ID"

Write-Host "Waiting 3 seconds for asynchronous event bus..."
Start-Sleep -Seconds 3

Write-Host "Checking process status..."
cmd /c "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=`"process status $PROCESS_ID --seed 127.0.0.1:20001`""

Write-Host "Terminating node..."
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
