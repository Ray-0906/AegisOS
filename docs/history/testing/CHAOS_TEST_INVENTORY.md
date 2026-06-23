# Chaos Test Inventory

This inventory catalogs the complexity and risk level of every test in the `aegis-test-cluster` module. Tests are scored based on whether they manipulate nodes, checkpoints, or the AegisFS layer, and how many raw `Thread.sleep()` calls they use.

**Risk Levels:**
- **HIGH**: Manipulates nodes AND (checkpoints OR AegisFS).
- **MEDIUM**: Manipulates nodes, OR uses 3+ sleeps, OR uses network partitioning / lease expiration hacks.
- **LOW**: No node manipulation, <3 sleeps, mostly static state testing.

## Inventory Table

| Test | Sleeps | Node Kill | Checkpoint | AegisFS | Leader Failover | Risk |
|------|--------|-----------|------------|---------|-----------------|------|
| AddOfflineVoterRejectedTest | 0 | NO | NO | NO | NO | LOW |
| ArtifactCacheReuseTest | 0 | NO | NO | NO | NO | LOW |
| ArtifactNotFoundTest | 0 | NO | NO | NO | NO | LOW |
| ArtifactRestartRecoveryTest | 2 | YES | NO | NO | NO | MEDIUM |
| ArtifactUploadAndDownloadTest | 0 | NO | NO | NO | NO | LOW |
| AuthoritativeMetadataSourceTest | 0 | YES | NO | YES | YES | **HIGH** |
| ChaosSoakTest | 1 | YES | NO | YES | YES | **HIGH** |
| CheckpointLocalityTest | 2 | NO | YES | YES | NO | MEDIUM |
| CheckpointPersistenceTest | 1 | NO | YES | YES | NO | LOW |
| CheckpointRetentionTest | 0 | NO | YES | YES | NO | LOW |
| CheckpointUploadCancellationTest | 3 | NO | YES | YES | NO | MEDIUM |
| CliMembershipIsolationTest | 0 | NO | NO | NO | NO | LOW |
| ConfigurationSurvivesRestartTest | 1 | YES | NO | NO | YES | MEDIUM |
| ContainerExecutionTest | 0 | YES | NO | NO | NO | MEDIUM |
| CorruptCheckpointRecoveryTest | 0 | YES | YES | YES | NO | **HIGH** |
| CorruptSnapshotRecoveryTest | 2 | YES | NO | YES | NO | **HIGH** |
| DuplicateExecutionPreventionTest | 2 | NO | NO | NO | NO | MEDIUM |
| ExecutionRecoveryAfterSnapshotTest | 1 | YES | NO | YES | NO | **HIGH** |
| HashTest | 0 | NO | NO | NO | NO | LOW |
| HotArtifactSpreadTest | 0 | NO | NO | NO | NO | LOW |
| InstallSnapshotAfterCompactionTest | 1 | NO | NO | NO | NO | MEDIUM |
| InstallSnapshotSuffixPreservationTest | 1 | NO | NO | YES | NO | MEDIUM |
| InstallSnapshotVerificationTest | 3 | YES | NO | NO | NO | MEDIUM |
| JobCancellationTest | 1 | NO | NO | NO | NO | LOW |
| JobLifecycleTest | 0 | NO | NO | NO | NO | LOW |
| JobLogPersistenceTest | 1 | NO | NO | YES | NO | LOW |
| JoinModeNonElectableTest | 1 | NO | NO | NO | NO | LOW |
| LeaderFailoverCheckpointTest | 0 | YES | YES | NO | YES | **HIGH** |
| LeaderFailoverDuringRecoveryTest | 0 | YES | NO | NO | YES | **HIGH** |
| LeaderFailoverJobRecoveryTest | 1 | YES | NO | NO | YES | MEDIUM |
| LeaderOnlyAuditSchedulerTest | 2 | YES | NO | YES | YES | **HIGH** |
| LocalityMetricsValidationTest | 0 | NO | YES | NO | NO | MEDIUM |
| LogCompactionTest | 0 | NO | NO | YES | NO | LOW |
| LogTruncationVerificationTest | 3 | YES | NO | YES | YES | **HIGH** |
| LongRunningCheckpointChaosTest | 1 | YES | YES | NO | YES | **HIGH** |
| MembershipVisibilityTest | 2 | NO | NO | NO | NO | LOW |
| MountPathTraversalTest | 0 | NO | NO | NO | NO | LOW |
| NonVoterGrantsVoteTest | 1 | YES | NO | NO | YES | MEDIUM |
| ObservedStateCollectorRemoteTest | 0 | NO | NO | YES | NO | LOW |
| OvernightSoakTest | 5+ | YES | NO | YES | YES | **HIGH** |
| PartitionSafetyTest | 2 | NO | NO | NO | YES | MEDIUM |
| PerformanceBenchmarks | 6+ | YES | NO | NO | YES | MEDIUM |
| Phase10ChaosMarathonTest | 1 | YES | NO | YES | YES | **HIGH** |
| Phase1Test | 0 | NO | NO | NO | NO | LOW |
| Phase2Test | 0 | YES | NO | NO | NO | MEDIUM |
| Phase3ChaosTest | 0 | YES | NO | NO | NO | MEDIUM |
| Phase3Test | 0 | YES | NO | NO | YES | MEDIUM |
| Phase4Test | 0 | YES | NO | YES | NO | **HIGH** |
| Phase5Test | 2 | NO | NO | NO | NO | LOW |
| Phase6Test | 2 | YES | YES | NO | NO | **HIGH** |
| Phase7Test | 0 | NO | NO | YES | NO | LOW |
| Phase8Test | 1 | NO | NO | YES | NO | LOW |
| Phase9Test | 1 | YES | NO | YES | YES | **HIGH** |
| RaftQuorumIsolationTest | 2 | YES | NO | NO | NO | MEDIUM |
| RecoveryAfterCompactionTest | 0 | YES | NO | YES | NO | **HIGH** |
| RecoveryDoesNotReplayUncommittedEntriesTest | 2 | YES | NO | NO | YES | MEDIUM |
| RepairCopyFailureTest | 1 | NO | NO | YES | NO | LOW |
| RepairExecutionSignOffTest | 1 | NO | NO | YES | NO | LOW |
| RepairLeaderFailoverTest | 2 | YES | NO | YES | YES | **HIGH** |
| RepairTaskRestartTest | 3 | YES | NO | YES | YES | **HIGH** |
| ResourceAllocatorSnapshotRecoveryTest | 3 | YES | NO | NO | NO | MEDIUM |
| SchedulerDeterminismTest | 0 | NO | NO | NO | NO | LOW |
| ScratchIsolationTest | 0 | NO | NO | NO | NO | LOW |
| SelfRemovalLeaderTest | 1 | NO | NO | NO | YES | MEDIUM |
| SingleNodeBootstrapTest | 0 | NO | NO | NO | NO | LOW |
| SingleNodeElectionTest | 0 | NO | NO | NO | NO | LOW |
| SnapshotBoundaryTermTest | 0 | NO | NO | YES | NO | LOW |
| SnapshotCheckpointRecoveryTest | 0 | NO | YES | NO | NO | LOW |
| SnapshotDuringExecutionTest | 0 | NO | NO | YES | NO | LOW |
| SnapshotRecoveryTest | 4 | YES | NO | YES | NO | **HIGH** |
| SnapshotRepairTaskRecoveryTest | 3 | YES | NO | YES | YES | **HIGH** |
| SnapshotSignOffTest | 2 | NO | NO | YES | NO | LOW |
| SnapshotStressCompactionTest | 3 | YES | NO | YES | NO | **HIGH** |
| SnapshotThresholdVerificationTest | 0 | NO | NO | YES | NO | LOW |
| SnapshotTransferTest | 3 | YES | NO | YES | NO | **HIGH** |
| Sprint4SignOffTest | 1 | NO | NO | YES | NO | LOW |
| StaleCheckpointFenceTest | 1 | NO | YES | NO | NO | MEDIUM |
| StaleExecutionArtifactTest | 1 | NO | NO | YES | NO | MEDIUM |
| StaleQueuedExecutionTest | 1 | NO | NO | NO | NO | LOW |
| StorageAuditRealityTest | 2 | NO | NO | YES | NO | LOW |
| StorageVerificationTest | 1 | NO | NO | YES | NO | LOW |
| TerminalStateFirstWriterWinsTest | 1 | NO | NO | NO | NO | LOW |
| TerminalStateOrderingTest | 1 | NO | NO | NO | NO | MEDIUM |
| VoterPromotionTest | 0 | YES | NO | NO | YES | MEDIUM |
| WorkerExitVsCancelRaceTest | 1 | NO | NO | NO | NO | LOW |
| WorkerFailureRecoveryTest | 0 | YES | NO | NO | NO | MEDIUM |
| WorkerFailureResumeTest | 0 | YES | YES | NO | NO | **HIGH** |
| WorkspaceCleanupTest | 0 | NO | NO | NO | NO | LOW |
| WorkspaceProvisioningTest | 0 | NO | NO | NO | NO | LOW |

## Summary
- **HIGH RISK Tests**: 21
- **MEDIUM RISK Tests**: 24
- **LOW RISK Tests**: 48

## Extracted Wait Patterns (for ClusterAwaiter)
Across the chaos tests, these distinct polling loops are currently implemented using raw `Thread.sleep` that should be abstracted into `ClusterAwaiter`:
1. `awaitCheckpointCreated(jobId, minSequence)`
2. `awaitArtifactReplication(fileId)`
3. `awaitLeaderElectionPoll()` (Many tests use a generic loop to find the leader)
4. `awaitRepairCompletion(repairId)`
5. `awaitSnapshotCreation(index)`
