# AegisOS v2.x REST API Reference

AegisOS exposes a fully-featured REST API to manage the cluster, jobs, distributed file system, and runtime processes. The API strictly follows `INV-054` ensuring all CLI operations map to corresponding REST endpoints.

## Base Path
All API requests must be prefixed with `/v1/`.
Example: `http://<node-ip>:<port>/v1/health`

---

## 1. Cluster & Membership

### Get Cluster Health
* **Endpoint**: `GET /v1/health`
* **Description**: Returns the operational status of the queried node.

### Get Node Roster
* **Endpoint**: `GET /v1/nodes`
* **Description**: Returns the list of all nodes currently discovered and active in the cluster.

### Get Current Leader
* **Endpoint**: `GET /v1/leader`
* **Description**: Identifies the currently elected Raft leader for the consensus group.

### Add Node to Consensus
* **Endpoint**: `POST /v1/admin/membership`
* **Description**: Proposes a new voter to the Raft consensus group. Requires leader forwarding if not sent to the leader.

### Remove Node from Consensus
* **Endpoint**: `DELETE /v1/admin/membership/{nodeId}`
* **Description**: Removes an existing voter from the Raft consensus group.

---

## 2. Distributed Files (AegisFS)

### List Files
* **Endpoint**: `GET /v1/files` or `GET /v1/files/{path}`
* **Description**: Lists contents of the distributed AegisFS at the specified logical path.

### Upload/Update File
* **Endpoint**: `PUT /v1/files/{path}`
* **Description**: Uploads a file fragment/chunk into the distributed filesystem at the specified path.

### Delete File
* **Endpoint**: `DELETE /v1/files/{path}`
* **Description**: Deletes a file from the distributed filesystem.

---

## 3. Artifact Registry

### List Artifacts
* **Endpoint**: `GET /v1/artifacts`
* **Description**: Retrieves a list of all registered execution artifacts (e.g., scripts, JARs, binaries) available for jobs.

### Upload Artifact
* **Endpoint**: `POST /v1/artifacts?name={artifactName}`
* **Description**: Uploads a new artifact payload into the registry. The binary stream should be sent as the request body.

---

## 4. Jobs & Orchestration

### List All Jobs
* **Endpoint**: `GET /v1/jobs`
* **Description**: Returns a summary list of all jobs currently tracked by the JobRegistry.

### Submit a New Job
* **Endpoint**: `POST /v1/jobs`
* **Description**: Submits a new distributed workload (job) to the cluster. The request body must contain the JobSpec payload detailing execution parameters, assigned artifact, and resource requirements.

### Get Job Details
* **Endpoint**: `GET /v1/jobs/{jobId}`
* **Description**: Returns detailed state and execution metadata for a specific job.

### Stream Job Logs
* **Endpoint**: `GET /v1/jobs/{jobId}/logs?stream={stdout|stderr}&executionId={id}`
* **Description**: Streams the distributed logs for a specific job directly from the node executing it (utilizing IPC overlay if the job is running on a remote node).
* **Parameters**: 
  * `stream` (optional): `stdout` (default) or `stderr`.
  * `executionId` (optional): Limits log retrieval to a specific execution attempt.

### Cancel Job
* **Endpoint**: `DELETE /v1/jobs/{jobId}`
* **Description**: Issues a cluster-wide cancellation command for the specified job, halting execution and evicting it from the active scheduler.

---

## 5. Runtime & Processes

### List Active Processes
* **Endpoint**: `GET /v1/processes`
* **Description**: Returns a list of all physical OS processes currently executing on this specific worker node.

### Get Process Details
* **Endpoint**: `GET /v1/processes/{processId}`
* **Description**: Returns execution metrics, TTL, state, and resource consumption for a specific local process.
