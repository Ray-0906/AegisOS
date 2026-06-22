# Repository Audit (v1.4.2)

## KEEP FOREVER (Identity of AegisOS)
- `README.md`
- `docs/API_CONTRACT_v1.md`
- `docs/ERROR_CONTRACT.md`
- `docs/GETTING_STARTED.md`
- `docs/SCORECARD.md`
- `.agents/AGENTS.md`
- `demo/` (and its contents)
- `aegis-examples/`
- `aegis-contract-test/`

## Documentation (`docs/`)

### KEEP (To be restructured)
- `GETTING_STARTED.md` -> `docs/getting-started/`
- `ARCHITECTURE.md` -> `docs/architecture/`
- `API_CONTRACT_v1.md` -> `docs/architecture/`
- `ERROR_CONTRACT.md` -> `docs/architecture/`
- `ADR-*.md` -> `docs/adr/`
- `SMOKE_TEST.md` -> `docs/operations/`
- `SCORECARD.md` -> `docs/governance/`
- `ROADMAP.md` -> `docs/governance/`
- `CHANGELOG.md` (if exists) -> `docs/history/`

### ARCHIVE (Move to `docs/history/`)
- `RETIREMENT_v1.3.2R.md`
- `ARCHITECTURE_API.md`
- `ARCHITECTURE_CLIENT.md`
- `ARCHITECTURE_OWNERSHIP.md`
- `ARCHITECTURE_PORTS.md`
- `ARCHITECTURE_v0.9.md`
- `ARCHITECTURE_v0.95.md`
- `V1_RUNTIME_DESIGN.md`
- `LOG_COMPACTION_DESIGN.md`
- `LOG_COMPACTION_IMPLEMENTATION_PLAN.md`
- `post-v1-roadmap.md`
- `releases/` (v1.1-baseline, v1.1-notes, v1.2-plan, etc.)
- `v0.4/` directory
- `DEBT_LEDGER.md` -> `docs/history/debt/`
- `KNOWN_LIMITATIONS.md` -> `docs/history/debt/`
- `TEST_DEBT.md` -> `docs/history/debt/`
- `TECH_DEBT.md` -> `docs/history/debt/`
- `TESTING_V03_RC1.md` -> `docs/history/testing/`
- `MANUAL_TESTING_V02.md` -> `docs/history/testing/`
- `CHAOS_TEST_INVENTORY.md` -> `docs/history/testing/`
- `CANARY_TESTS.md` -> `docs/history/testing/`
- `GOSSIP_NOTES.md` -> `docs/history/`
- `RAFT_NOTES.md` -> `docs/history/`
- `OBSERVABILITY_GAP_AUDIT.md` -> `docs/history/debt/`
- `PR2_INSTRUMENTATION.md` -> `docs/history/`
- `PR4_INSTRUMENTATION.md` -> `docs/history/`
- `SCHEDULER_METRICS.md` -> `docs/history/`
- `TEST_PRIMITIVES.md` -> `docs/history/testing/`
- `handoff.md` -> `docs/history/`

## Scripts (`*.ps1`, `*.sh`, `scripts/`)

### KEEP
- `demo/start_cluster.ps1`
- `demo/upload_artifact.ps1`
- `demo/run_job.ps1`
- `demo/cleanup.ps1`
- `test_replication.ps1` -> `scripts/tests/`
- `test_failover.ps1` -> `scripts/tests/`
- `test_rejoin.ps1` -> `scripts/tests/`
- `test_catchup.ps1` -> `scripts/tests/`
- `test_scheduler_failover.ps1` -> `scripts/tests/`
- `test_exactly_once.ps1` -> `scripts/tests/`
- `test_job_recovery.ps1` -> `scripts/tests/`
- `test_worker_death.ps1` -> `scripts/tests/`
- `test_isolation.ps1` -> `scripts/tests/`
- `scripts/stress-suite.ps1` -> `scripts/tests/`
- `scripts/staircase.ps1` -> `scripts/maintenance/`

### DELETE (Pending Reference Audit)
- `demo.ps1`
- `run_loop.ps1`
- `run_phase5_stress.ps1`
- `run_stage1.ps1`
- `run_stage2.ps1`
- `run_stage3.ps1`
- `test_demo.ps1`
- `test_artifacts.ps1`
- `test_v02.ps1`
- `build_test_artifacts.ps1`
- `check_exceptions.ps1`
- `test_client_quorum.ps1`
- `test_heartbeat_failover.ps1`
- `test_jobs.ps1`
- `test_migration.ps1`
- `test_recovery_race.ps1`
- `test_resilience.ps1`
- `test_sandbox.ps1`
- `test_scheduler.ps1`
- `test_scheduler_correctness.ps1`

## Generated Files & Logs

### DELETE
- `node1/`, `node2/`, `node3/` (and `.err`, `.out` files)
- `data/` directory
- `test-cluster/` directory
- `*.err`, `*.out`, `*.log`
- `dummy.txt`, `my_test_file.txt`, `recovered.txt`, `test_downloaded.txt`
- `build_artifacts/` directory
- `myJob/` directory
- `task_pr2_instrument.py`, `task_pr3_instrument.py`, `task_pr4_instrument.py`

### GITIGNORE (.gitignore additions)
```gitignore
# Local clusters
node*/
data/
test-cluster/

# Runtime artifacts
*.log
*.out
*.err

# Generated files
dist/
target/

# Temp outputs
recovered.txt
test_downloaded.txt
```

## Repository Structure (Canonical)

The root will look like:
```text
AegisOS/
├── aegis-api/
├── aegis-cli/
├── aegis-client/
├── aegis-node/
├── aegis-consensus/
├── aegis-discovery/
├── aegis-fs/
├── aegis-scheduler/
├── aegis-runtime/
├── aegis-test-cluster/
├── aegis-contract-test/
├── aegis-examples/
├── bin/
├── demo/
├── scripts/
│   ├── tests/
│   └── maintenance/
├── docs/
│   ├── getting-started/
│   ├── architecture/
│   ├── adr/
│   ├── operations/
│   ├── history/
│   │   ├── debt/
│   │   └── testing/
│   └── governance/
└── README.md
```
