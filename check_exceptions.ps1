param (
    [string]$LogDir = "C:\Users\astra\.gemini\antigravity\brain\8e6f21a0-7205-4ece-b4ec-f1928e6ea87b\.system_generated\tasks"
)

$whitelist = Get-Content "C:\Users\astra\Desktop\AegisOS\expected_exceptions.txt"

# Find all Exception/Error lines in recent log files
$logFiles = Get-ChildItem -Path $LogDir -Filter "*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 10
$unexpectedCount = 0

foreach ($file in $logFiles) {
    $lines = Get-Content $file.FullName
    foreach ($line in $lines) {
        if ($line -match "Exception|Error") {
            $isExpected = $false
            foreach ($allowed in $whitelist) {
                if ($line -match $allowed) {
                    $isExpected = $true
                    break
                }
            }
            if (-not $isExpected) {
                # Some other common ignored stuff from surefire
                if ($line -notmatch "AssertionFailedError" -and $line -notmatch "Test execution" -and $line -notmatch "Errors: 0" -and $line -notmatch "SurefireProvider") {
                    Write-Host "UNEXPECTED: $line"
                    $unexpectedCount++
                }
            }
        }
    }
}

Write-Host "Total Unexpected Exceptions: $unexpectedCount"
if ($unexpectedCount -gt 0) {
    exit 1
}
exit 0
