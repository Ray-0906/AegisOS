Remove-Item -Recurse -Force -ErrorAction Ignore node*-data

$Node1 = Start-Process -NoNewWindow -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar", "start", "--bootstrap", "--rest-port", "20000", "--port", "7000", "--home", "node1-data" -PassThru
Start-Sleep -Seconds 3

$Node2 = Start-Process -NoNewWindow -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar", "start", "--seed", "127.0.0.1:7000", "--rest-port", "20001", "--port", "7001", "--home", "node2-data" -PassThru

$Node3 = Start-Process -NoNewWindow -FilePath "java" -ArgumentList "-jar", "aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar", "start", "--seed", "127.0.0.1:7000", "--rest-port", "20002", "--port", "7002", "--home", "node3-data" -PassThru
Start-Sleep -Seconds 15

$ArtEmitRaw = java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar artifact upload emitter.py --seed 127.0.0.1:20000 | Select-Object -Last 1
$ArtEmit = [regex]::Match($ArtEmitRaw, '[a-f0-9]{64}').Value

$ArtRecvRaw = java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar artifact upload receiver.js --seed 127.0.0.1:20000 | Select-Object -Last 1
$ArtRecv = [regex]::Match($ArtRecvRaw, '[a-f0-9]{64}').Value

$ProcRecvRaw = java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar process submit --artifact $ArtRecv --command "node {artifact}" --cpu 1 --memory 128 --seed 127.0.0.1:20000 | Select-Object -Last 1
$ProcRecv = [regex]::Match($ProcRecvRaw, '[a-f0-9-]{36}').Value
Start-Sleep -Seconds 2

java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar process submit --artifact $ArtEmit --command "python {artifact}" --pipe-to $ProcRecv --cpu 1 --memory 128 --seed 127.0.0.1:20000

Start-Sleep -Seconds 15
Stop-Process -Id $Node1.Id -Force

Start-Sleep -Seconds 10
Stop-Process -Id $Node2.Id -Force
Stop-Process -Id $Node3.Id -Force
