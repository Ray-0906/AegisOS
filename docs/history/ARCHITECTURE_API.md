# Architecture API (v1.3)

AegisOS nodes expose a REST API to service external clients (CLI, Web UI, SDKs). This API runs entirely independent of the internal custom TCP P2P networking used for cluster consensus.

All endpoints operate over HTTP/REST using JSON schemas and are versioned under `/v1/`. As per **INV-042**, the public API strictly hides all consensus implementation details (Raft, terms, indexes, Gossip) and only exposes platform concepts (Cluster, Nodes, Jobs, Files, Membership).

## Shared DTOs (`aegis-api` module)

The request and response bodies for these endpoints will map directly to shared DTOs defined in the `aegis-api` module. These DTOs contain zero cluster logic.

- `LeaderResponse`: `{ "leaderId": "...", "apiPort": 20001 }`
- `NodeResponse`: `{ "nodeId": "...", "status": "ALIVE", "apiPort": 20001 }`
- `JobResponse`: `{ "jobId": "...", "status": "COMPLETED" }`
- `ErrorResponse`: `{ "error": "...", "code": 400 }`

## REST Endpoints

### Cluster
- `GET /v1/health`: Returns 200 OK if the node is alive and responsive.
- `GET /v1/nodes`: Lists all known nodes and their availability states.
- `GET /v1/leader`: Returns the current leader's Node ID and REST API port.

### Files
- `PUT /v1/files/{path}`: Uploads a file stream. (Server-side chunking will be implemented by the Leader).
- `GET /v1/files/{path}`: Downloads a file stream.
- `DELETE /v1/files/{path}`: Deletes a file.
- `GET /v1/files`: Lists files in the filesystem.

### Jobs
- `POST /v1/jobs`: Submits a new job for execution. Returns the assigned `jobId`.
- `GET /v1/jobs`: Lists active/recent jobs.
- `GET /v1/jobs/{id}`: Returns the status and metadata for a specific job.
- `DELETE /v1/jobs/{id}`: Cancels a running job.

### Platform Administration
*Note: Dangerous operator actions are isolated under `/admin/` and hidden from normal end-users.*
- `POST /v1/admin/membership/add-voter`: Submits a request to expand the cluster. Body: `{ "targetNodeId": "..." }`
- `POST /v1/admin/membership/remove-voter`: Submits a request to shrink the cluster. Body: `{ "targetNodeId": "..." }`

## Error Contracts

- **400 Bad Request**: Invalid JSON or missing required fields.
- **404 Not Found**: The requested resource (file, job, node) does not exist.
- **503 Service Unavailable**: The cluster is currently unavailable (e.g., no leader elected).
- **307 Temporary Redirect**: If a modifying request (e.g., `PUT /v1/files`) is sent to a Follower, the Follower will return `307` pointing the client to the Leader's REST API endpoint.
