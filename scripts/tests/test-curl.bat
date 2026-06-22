@echo off
if exist "target\node-1" rmdir /s /q "target\node-1"
start "Node 1" cmd /c "mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args=""start --port 9000 --bootstrap --home target/node-1"""
echo Waiting 15 seconds for bootstrap to init...
timeout /t 15 /nobreak >nul
echo Querying Node 1 Leader...
curl http://127.0.0.1:20000/v1/leader
echo.
taskkill /fi "WINDOWTITLE eq Node 1*" /t /f >nul
