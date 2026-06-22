@echo off
echo Starting AegisOS Node...
start /b cmd /c "mvn exec:java -pl aegis-node -Dexec.mainClass=com.aegisos.node.Main -Dexec.args=\"--port 7000 --api-port 20000 --rest-port 20001 --data-dir async-test-dir --bootstrap\" > async-node.log 2>&1"

echo Waiting for node to boot...
timeout /t 5 /nobreak > nul

echo Submitting process...
for /f "tokens=*" %%i in ('mvn exec:java -q -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process submit --artifact async-test-art --cpu 2 --memory 512 --seed 127.0.0.1:20000"') do set PROCESS_ID=%%i

echo Process ID: %PROCESS_ID%

echo Waiting 3 seconds for asynchronous event bus...
timeout /t 3 /nobreak > nul

echo Checking process status...
mvn exec:java -q -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="process status %PROCESS_ID% --seed 127.0.0.1:20000"

echo Terminating node...
taskkill /F /IM java.exe /T > nul 2>&1
