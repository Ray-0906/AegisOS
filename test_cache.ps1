$ErrorActionPreference = "Stop"

$env:CP="aegis-cli/target/aegis.jar"

function Run-Aegis {
    param([string]$ArgsStr)
    Invoke-Expression "java -cp `"$env:CP`" com.aegisos.cli.AegisCLI $ArgsStr"
}

Write-Host "Cleaning up old data..."
rm -Recurse -Force data -ErrorAction SilentlyContinue
mkdir data | Out-Null

Write-Host "Starting cluster..."
$p1 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput data/node1.log -RedirectStandardError data/node1_err.log -WindowStyle Hidden -PassThru
$p2 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node2.log -RedirectStandardError data/node2_err.log -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 5
Write-Host "Uploading artifact..."
$uploadOut = Run-Aegis "artifact upload ../aegis-demo-job/target/aegis-demo-job-1.0.jar --seed 127.0.0.1:9001"
$uploadOutStr = $uploadOut -join " "
$artifactId = ""
if ($uploadOutStr -match "artifact:\s*([a-f0-9]+)") {
    $artifactId = $matches[1]
}
Write-Host "Artifact ID: $artifactId"

Write-Host "Running Job 1 (Should be CACHE MISS)..."
Run-Aegis "run --seed 127.0.0.1:9001 --artifact $artifactId com.example.WordCounter `"test`"" | Out-Null

Write-Host "Running Job 2 (Should be CACHE HIT)..."
Run-Aegis "run --seed 127.0.0.1:9001 --artifact $artifactId com.example.WordCounter `"test`"" | Out-Null

Write-Host "Checking logs for CACHE MISS and CACHE HIT:"
Select-String "CACHE " data/node*.log -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_.Line }

Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p2.Id -Force -ErrorAction SilentlyContinue
