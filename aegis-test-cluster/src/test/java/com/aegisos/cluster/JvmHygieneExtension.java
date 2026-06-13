package com.aegisos.cluster;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global test hooks: logs inherited JVM state before each test and resets shared
 * contamination sources afterward.
 */
public final class JvmHygieneExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        String test = context.getDisplayName();
        String leaseProp = System.getProperty("aegis.lease.duration.ms");
        long effectiveLeaseMs = readEffectiveLeaseMs();
        System.out.println("[JVM-HYGIENE] before " + test
                + " leaseProperty=" + (leaseProp == null ? "<unset>" : leaseProp)
                + " effectiveLeaseMs=" + effectiveLeaseMs
                + " reservationTtlProperty=" + System.getProperty("aegis.reservation.ttl", "<unset>")
                + " delayAfterLost=" + (System.getProperty("aegis.test.delay_after_lost") != null)
                + " delayUploadLogs=" + (System.getProperty("aegis.test.delay_upload_logs") != null));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TestJvmHygiene.clearAll();
    }

    private static long readEffectiveLeaseMs() {
        try {
            var field = Class.forName("com.aegisos.runtime.JobSupervisor")
                    .getDeclaredField("LEASE_DURATION_MS");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (ReflectiveOperationException e) {
            return -1L;
        }
    }
}
