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
Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput data/node1.log -RedirectStandardError data/node1_err.log -WindowStyle Hidden
Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node2.log -RedirectStandardError data/node2_err.log -WindowStyle Hidden
Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node3 --port 9003 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node3.log -RedirectStandardError data/node3_err.log -WindowStyle Hidden

Start-Sleep -Seconds 5
Write-Host "Nodes:"
Run-Aegis "nodes --seed 127.0.0.1:9001"

Write-Host "Uploading artifact..."
$uploadOut = Run-Aegis "artifact upload ../aegis-demo-job/target/aegis-demo-job-1.0.jar --seed 127.0.0.1:9001"
Write-Host $uploadOut

# Extract artifact ID
$artifactId = ""
$uploadOutStr = $uploadOut -join " "
if ($uploadOutStr -match "artifact:\s*([a-f0-9]+)") {
    $artifactId = $matches[1]
} else {
    Write-Host "Failed to extract artifact ID"
    exit 1
}
Write-Host "Artifact ID: $artifactId"

Write-Host "Running job #1..."
Run-Aegis "run --seed 127.0.0.1:9001 --artifact $artifactId com.example.WordCounter `"First test`""

Write-Host "Running job #2 (should hit cache)..."
Run-Aegis "run --seed 127.0.0.1:9001 --artifact $artifactId com.example.WordCounter `"Second test`""

Write-Host "Done. Check data/node*.log for Cache miss/hit."
