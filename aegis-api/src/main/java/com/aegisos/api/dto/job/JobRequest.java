package com.aegisos.api.dto.job;

import java.util.List;

public record JobRequest(
        String type,
        String artifact,
        String entrypoint,
        List<String> args,
        JobResources resources
) {
}
