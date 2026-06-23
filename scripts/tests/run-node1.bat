@echo off
mvn -q exec:java -pl aegis-cli -Dexec.mainClass=com.aegisos.cli.AegisCLI -Dexec.args="start --port 9000 --bootstrap --home target/node-1" > target\n1.log 2>&1
