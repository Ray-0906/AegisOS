# AegisOS API Contract v1

The `/v1` namespace is a guaranteed product contract.

## Cluster Operations

### `GET /v1/health`
Returns the health status of the node.
- **Response**: `{"status": "UP"}`
- **Errors**: `503 SERVICE_UNAVAILABLE`

### `GET /v1/nodes`
Returns the list of known nodes in the cluster.
- **Response**: Array of node objects `[{"nodeId": "...", "status": "ALIVE", ...}]`
- **Errors**: `503 SERVICE_UNAVAILABLE`

### `GET /v1/leader`
Returns the current Raft leader.
- **Response**: `{"leaderId": "...", "apiPort": 20001}`
- **Errors**: `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

## File Operations

### `GET /v1/files`
Lists all files in the cluster.
- **Response**: `{"files": [...]}`
- **Errors**: `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `GET /v1/files/{path}`
Downloads the raw file content.
- **Response**: raw bytes (application/octet-stream)
- **Errors**: `404 RESOURCE_NOT_FOUND`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `PUT /v1/files/{path}`
Uploads file content.
- **Request Body**: raw bytes
- **Response**: `{"path": "...", "size": 123}`
- **Errors**: `400 INVALID_REQUEST`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

## Artifact Operations

### `GET /v1/artifacts`
Lists registered artifacts.
- **Response**: Array of artifacts
- **Errors**: `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `POST /v1/artifacts?name={name}`
Uploads an executable artifact.
- **Request Body**: raw jar bytes
- **Response**: `{"artifactId": "sha256...", "name": "..."}`
- **Errors**: `400 INVALID_REQUEST`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

## Job Operations

### `GET /v1/jobs`
Lists all jobs.
- **Response**: Array of jobs
- **Errors**: `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `POST /v1/jobs`
Submits a new job.
- **Request Body**: JSON job spec
- **Response**: `{"jobId": "uuid..."}`
- **Errors**: `400 INVALID_REQUEST`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `GET /v1/jobs/{id}`
Gets status of a specific job.
- **Response**: Job status object
- **Errors**: `404 RESOURCE_NOT_FOUND`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `DELETE /v1/jobs/{id}`
Cancels a running or queued job.
- **Response**: `{"jobId": "...", "status": "CANCELLED"}`
- **Errors**: `404 RESOURCE_NOT_FOUND`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `GET /v1/jobs/{id}/logs?stream={stdout|stderr}&executionId={execId}`
Streams the job execution logs.
- **Response**: raw text logs
- **Errors**: `404 RESOURCE_NOT_FOUND`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

## Administration

### `POST /v1/admin/membership`
Adds a new voting member to the Raft cluster.
- **Request Body**: `{"nodeId": "..."}`
- **Response**: `{"status": "SUCCESS"}`
- **Errors**: `400 INVALID_REQUEST`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`

### `DELETE /v1/admin/membership/{nodeId}`
Removes a voting member from the Raft cluster.
- **Response**: `{"status": "SUCCESS"}`
- **Errors**: `400 INVALID_REQUEST`, `503 NOT_LEADER`, `503 SERVICE_UNAVAILABLE`
