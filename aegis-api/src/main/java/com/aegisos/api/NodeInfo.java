package com.aegisos.api;

/** A snapshot of a peer's identity, address, and liveness status. */
public record NodeInfo(String nodeId, String address, String status) {
}
