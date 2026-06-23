#!/bin/bash
set -e

rm -rf node*-data

java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar start --bootstrap --rest-port 20000 --port 7000 --home node1-data &
NODE1_PID=$!
sleep 3

java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar start --seed 127.0.0.1:7000 --rest-port 20001 --port 7001 --home node2-data &
NODE2_PID=$!

java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar start --seed 127.0.0.1:7000 --rest-port 20002 --port 7002 --home node3-data &
NODE3_PID=$!
sleep 10

ART_EMIT=$(java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar artifact upload emitter.py --seed 127.0.0.1:20000 | grep -oE '[a-f0-9]{64}')
ART_RECV=$(java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar artifact upload receiver.js --seed 127.0.0.1:20000 | grep -oE '[a-f0-9]{64}')

PROC_RECV=$(java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar process submit --artifact $ART_RECV --command "node {artifact}" --cpu 1 --memory 128 --seed 127.0.0.1:20000 | grep -oE '[a-f0-9-]{36}')
sleep 2

java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar process submit --artifact $ART_EMIT --command "python {artifact}" --pipe-to $PROC_RECV --cpu 1 --memory 128 --seed 127.0.0.1:20000

sleep 15
kill -9 $NODE1_PID

wait
