$count = 0
while ($true) {
    $count++
    Write-Host "Running iteration $count..."
    mvn test "-Dtest=AddOfflineVoterRejectedTest,ArtifactCacheReuseTest,ArtifactNotFoundTest,ArtifactRestartRecoveryTest,ArtifactUploadAndDownloadTest,AuthoritativeMetadataSourceTest,CheckpointLocalityTest,CheckpointPersistenceTest,CheckpointRetentionTest,CliMembershipIsolationTest,ConfigurationSurvivesRestartTest,CorruptCheckpointRecoveryTest" > test_run.log 2>&1
    
    # Check if CorruptCheckpointRecoveryTest failed
    $failed = Select-String "CorruptCheckpointRecoveryTest" test_run.log | Select-String "<<< FAILURE"
    if ($failed) {
        Write-Host "CorruptCheckpointRecoveryTest failed on iteration $count!"
        break
    }
}
