package com.aegisos.runtime.container;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory image registry for v1.1 trusted-images-only policy.
 */
public class MemoryImageRegistry implements ImageRegistry {
    
    private final Set<String> trustedImages = ConcurrentHashMap.newKeySet();

    public void trust(String image) {
        trustedImages.add(image);
    }

    public void revoke(String image) {
        trustedImages.remove(image);
    }

    @Override
    public boolean isTrusted(String image) {
        return trustedImages.contains(image);
    }
}
