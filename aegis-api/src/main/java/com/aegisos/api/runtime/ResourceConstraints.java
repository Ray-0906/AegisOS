package com.aegisos.api.runtime;

public record ResourceConstraints(int requiredCpuCores, long requiredMemoryMb, boolean requireGpu) {}
