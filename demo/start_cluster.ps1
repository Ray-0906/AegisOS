# start_cluster.ps1
$ErrorActionPreference = "Stop"

Write-Host "Starting bootstrap node..."
Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-node\target\aegis-node-0.1.0-SNAPSHOT.jar", "--port", "9000", "--api-port", "20000", "--id", "bootstrap", "--bootstrap", "--data-dir", "aegis_data\bootstrap" -WindowStyle Hidden -PassThru | Out-Null

Start-Sleep -Seconds 2

Write-Host "Starting seed node 1..."
Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-node\target\aegis-node-0.1.0-SNAPSHOT.jar", "--port", "9001", "--api-port", "20001", "--id", "seed1", "--seed", "127.0.0.1:9000", "--data-dir", "aegis_data\seed1" -WindowStyle Hidden -PassThru | Out-Null

Write-Host "Starting seed node 2..."
Start-Process -FilePath "java" -ArgumentList "-jar", "aegis-node\target\aegis-node-0.1.0-SNAPSHOT.jar", "--port", "9002", "--api-port", "20002", "--id", "seed2", "--seed", "127.0.0.1:9000", "--data-dir", "aegis_data\seed2" -WindowStyle Hidden -PassThru | Out-Null

Start-Sleep -Seconds 2

Write-Host "Adding voters to the cluster..."
java -jar aegis-cli\target\aegis.jar admin add-voter seed1 --seed 127.0.0.1:9000
java -jar aegis-cli\target\aegis.jar admin add-voter seed2 --seed 127.0.0.1:9000

Start-Sleep -Seconds 2
Write-Host "Cluster started successfully!"
java -jar aegis-cli\target\aegis.jar nodes --seed 127.0.0.1:9000
