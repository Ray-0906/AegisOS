package com.aegisos.cluster;

import com.aegisos.network.NetworkLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Resets JVM-global test hooks that survive across test methods when Surefire reuses a fork.
 * Called automatically by {@link JvmHygieneExtension} after every test.
 */
public final class TestJvmHygiene {

    private TestJvmHygiene() {
    }

    /** Clears all known shared-JVM contamination sources used by integration tests. */
    public static void clearAll() {
        NetworkLayer.clearMessageFilter();
        clearAegisSystemProperties();
    }

    private static void clearAegisSystemProperties() {
        List<String> keys = new ArrayList<>();
        System.getProperties().stringPropertyNames().forEach(name -> {
            if (name.startsWith("aegis.")) {
                keys.add(name);
            }
        });
        for (String key : keys) {
            System.clearProperty(key);
        }
    }
}
