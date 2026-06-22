@echo off
setlocal enabledelayedexpansion

echo Cleanup any previous test data
if exist "target\node-1" rmdir /s /q "target\node-1"
if exist "target\node-2" rmdir /s /q "target\node-2"
if exist "target\node-3" rmdir /s /q "target\node-3"

echo Starting Node 1 (port 9000, REST 20000) as bootstrap...
start "Node 1" cmd /c "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=""start --port 9000 --bootstrap --home target/node-1"""

echo Waiting 15 seconds for bootstrap to init...
ping 127.0.0.1 -n 16 > nul

echo Starting Node 2 (port 9001, REST 20001)...
start "Node 2" cmd /c "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=""start --port 9001 --home target/node-2 --seed 127.0.0.1:9000"""

echo Starting Node 3 (port 9002, REST 20002)...
start "Node 3" cmd /c "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=""start --port 9002 --home target/node-3 --seed 127.0.0.1:9000"""

echo Waiting 20 seconds for leader election...
ping 127.0.0.1 -n 21 > nul

echo.
echo --- Executing process submit against Node 1 (REST 20000) ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process submit --artifact raft-test-art --cpu 1 --memory 256 --seed 127.0.0.1:20000" > target\submit.out
type target\submit.out

for /f "delims=" %%a in (target\submit.out) do set "PID=%%a"
echo Captured ProcessId: %PID%

echo.
echo --- Executing process status against Node 2 (REST 20001) ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process status %PID% --seed 127.0.0.1:20001"

echo.
echo --- Executing process status against Node 3 (REST 20002) ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process status %PID% --seed 127.0.0.1:20002"

echo.
echo --- Simulating Leader Crash (Killing Node 1) ---
taskkill /fi "WINDOWTITLE eq Node 1*" /t /f >nul

echo Waiting 15 seconds for new leader election...
ping 127.0.0.1 -n 16 > nul

echo.
echo --- Querying surviving cluster (Node 2 - REST 20001) for process list ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process list --seed 127.0.0.1:20001"

echo.
echo --- Querying surviving cluster (Node 3 - REST 20002) for process list ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process list --seed 127.0.0.1:20002"

echo.
echo Cleaning up remaining nodes...
taskkill /fi "WINDOWTITLE eq Node 2*" /t /f >nul
taskkill /fi "WINDOWTITLE eq Node 3*" /t /f >nul
taskkill /im java.exe /f >nul 2>nul
echo Done!
