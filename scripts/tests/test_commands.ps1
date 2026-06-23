Write-Host "Killing old java processes..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue

Write-Host "Cleaning data..."
if (Test-Path "data") { Remove-Item -Recurse -Force "data" }
New-Item -ItemType Directory -Force "data" | Out-Null

Write-Host "Starting cluster..."
$global:p1 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node1 --port 9001 --seed 127.0.0.1:9001" -WindowStyle Hidden -PassThru
$global:p2 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -WindowStyle Hidden -PassThru
$global:p3 = Start-Process java -ArgumentList "-cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 10

Write-Host "`n=== TESTING CLUSTER COMMAND ==="
java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI cluster --seed 127.0.0.1:9001

Write-Host "`n=== TESTING HEALTH COMMAND ==="
java -cp aegis-cli/target/aegis.jar com.aegisos.cli.AegisCLI health --seed 127.0.0.1:9001

Write-Host "`nStopping cluster..."
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
