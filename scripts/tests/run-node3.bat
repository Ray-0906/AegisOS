@echo off
mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="start --port 9002 --home target/node-3 --seed 127.0.0.1:9000" > target\n3.log 2>&1
