# AegisOS Error Contract

This document standardizes the HTTP error codes that AegisOS API endpoints may return. Every endpoint must use these codes instead of ad-hoc errors.

## Standard Error Codes

### `400 INVALID_REQUEST`
Used when the client sends a malformed request, invalid parameters, or bad data. 
- E.g., `POST /v1/admin/membership` with an empty `nodeId`.
- E.g., `PUT /v1/files/` with an invalid path.

### `404 RESOURCE_NOT_FOUND`
Used when the requested resource does not exist in the cluster state.
- E.g., `GET /v1/jobs/unknown-id`
- E.g., `GET /v1/files/missing-file.txt`

### `405 METHOD_NOT_ALLOWED`
Used when the client requests an existing path with an unsupported HTTP method.
- E.g., `POST /v1/leader`

### `409 CONFLICT`
Used when a request conflicts with the current state of the cluster or resource.

### `503 NOT_LEADER`
Used when the client sends a mutative request (or strongly consistent read) to a node that is not currently the Raft leader.
Clients receiving this error should inspect the `leaderId` and `apiPort` in the error response and retry against the known leader.

### `503 SERVICE_UNAVAILABLE`
Used for general platform unviability, cluster-wide outages, no leader being elected, or internal server errors preventing the request from succeeding.
