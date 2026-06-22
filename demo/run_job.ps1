# run_job.ps1
$ErrorActionPreference = "Stop"

if ($args.Length -lt 1) {
    Write-Host "Usage: .\run_job.ps1 <ARTIFACT_ID>"
    exit 1
}

$artifactId = $args[0]

Write-Host "Submitting PrimeCounter job..."
java -jar aegis-cli\target\aegis.jar run --artifact $artifactId --seed 127.0.0.1:9000 com.aegisos.examples.PrimeCounter 100000

Write-Host ""
Write-Host "You can list active jobs to find the Job ID:"
Write-Host "  java -jar aegis-cli\target\aegis.jar jobs list --seed 127.0.0.1:9000"
Write-Host ""
Write-Host "Then check its logs:"
Write-Host "  java -jar aegis-cli\target\aegis.jar jobs logs <JOB_ID> --seed 127.0.0.1:9000"
