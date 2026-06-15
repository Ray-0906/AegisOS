package com.aegisos.runtime.container;

/**
 * Service to validate whether a container image is trusted.
 * v1.1 enforces a trusted-images-only policy.
 */
public interface ImageRegistry {
    /**
     * @param image the image name/reference
     * @return true if the image is allowed to run on this cluster
     */
    boolean isTrusted(String image);
}
