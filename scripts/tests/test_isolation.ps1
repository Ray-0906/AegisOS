$ErrorActionPreference = "Stop"

$env:CP="aegis-cli/target/aegis.jar"

function Run-Aegis {
    param([string]$ArgsStr)
    Invoke-Expression "java -cp `"$env:CP`" com.aegisos.cli.AegisCLI $ArgsStr"
}

Write-Host "Creating Jar A..."
mkdir tempA -ErrorAction SilentlyContinue | Out-Null
mkdir tempA/com/example -ErrorAction SilentlyContinue | Out-Null
@"
package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class Version implements AegisJob<String> {
    public Version() {}
    public Version(String[] args) {}
    public String execute(JobContext ctx) { return "A"; }
}
"@ | Out-File -Encoding ASCII tempA/com/example/Version.java
javac -cp $env:CP tempA/com/example/Version.java
jar cf tempA.jar -C tempA .

Write-Host "Creating Jar B..."
mkdir tempB -ErrorAction SilentlyContinue | Out-Null
mkdir tempB/com/example -ErrorAction SilentlyContinue | Out-Null
@"
package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class Version implements AegisJob<String> {
    public Version() {}
    public Version(String[] args) {}
    public String execute(JobContext ctx) { return "B"; }
}
"@ | Out-File -Encoding ASCII tempB/com/example/Version.java
javac -cp $env:CP tempB/com/example/Version.java
jar cf tempB.jar -C tempB .

Write-Host "Cleaning up old data..."
rm -Recurse -Force data -ErrorAction SilentlyContinue
mkdir data | Out-Null

Write-Host "Starting cluster..."
$p1 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node1 --port 9001" -RedirectStandardOutput data/node1.log -RedirectStandardError data/node1_err.log -WindowStyle Hidden -PassThru
$p2 = Start-Process java -ArgumentList "-cp `"$env:CP`" com.aegisos.cli.AegisCLI start --home data/node2 --port 9002 --seed 127.0.0.1:9001" -RedirectStandardOutput data/node2.log -RedirectStandardError data/node2_err.log -WindowStyle Hidden -PassThru

Start-Sleep -Seconds 5
Write-Host "Uploading artifact A..."
$uploadA = Run-Aegis "artifact upload tempA.jar --seed 127.0.0.1:9001"
$uploadAStr = $uploadA -join " "
$artifactIdA = ""
if ($uploadAStr -match "artifact:\s*([a-f0-9]+)") { $artifactIdA = $matches[1] }

Write-Host "Uploading artifact B..."
$uploadB = Run-Aegis "artifact upload tempB.jar --seed 127.0.0.1:9001"
$uploadBStr = $uploadB -join " "
$artifactIdB = ""
if ($uploadBStr -match "artifact:\s*([a-f0-9]+)") { $artifactIdB = $matches[1] }

Write-Host "Artifact A: $artifactIdA"
Write-Host "Artifact B: $artifactIdB"

Write-Host "Running Job A..."
$resA = Run-Aegis "run --seed 127.0.0.1:9001 --artifact $artifactIdA com.example.Version"
Write-Host "Output A: $resA"

Write-Host "Running Job B..."
$resB = Run-Aegis "run --seed 127.0.0.1:9001 --artifact $artifactIdB com.example.Version"
Write-Host "Output B: $resB"

Stop-Process -Id $p1.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $p2.Id -Force -ErrorAction SilentlyContinue

rm -Recurse -Force tempA tempB tempA.jar tempB.jar -ErrorAction SilentlyContinue
