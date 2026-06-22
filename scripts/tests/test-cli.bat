@echo off
set "ARGS_SUBMIT=process submit --artifact e2e-test-artifact --cpu 2 --memory 1024 --seed 127.0.0.1:20000"
set "ARGS_LIST=process list --seed 127.0.0.1:20000"

echo --- Submit ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="%ARGS_SUBMIT%" > target\submit.out
type target\submit.out

for /f "delims=" %%a in (target\submit.out) do set "PID=%%a"

echo Captured PID: %PID%

echo.
echo --- Status ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process status %PID% --seed 127.0.0.1:20000"

echo.
echo --- List ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="%ARGS_LIST%"

echo.
echo --- Cancel ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process cancel %PID% --seed 127.0.0.1:20000"

echo.
echo --- Status (After Cancel) ---
call mvn -q exec:java -pl aegis-cli -Dexec.mainClass="com.aegisos.cli.AegisCLI" -Dexec.args="process status %PID% --seed 127.0.0.1:20000"
