package com.aegisos.scheduler;

import com.aegisos.proto.JobSpec;
import com.aegisos.proto.ResourceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Manages soft reservations and hard allocations for a single node's resources.
 */
public class ResourceAllocator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ResourceAllocator.class);

    private final int totalCpuCores;
    private final long totalMemoryMb;
    private final long softReservationTtlMs = Long.getLong("aegis.reservation.ttl", 60000);

    private int hardAllocatedCpu = 0;
    private long hardAllocatedMem = 0;
    
    private int softReservedCpu = 0;
    private long softReservedMem = 0;

    public record SoftReservation(String reservationId, long schedulerEpoch, String jobId, ResourceRequest request, long expiresAt) {}

    private final Map<String, SoftReservation> softReservations = new ConcurrentHashMap<>();
    private final Map<String, ResourceRequest> hardAllocations = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().unstarted(r));

    public ResourceAllocator(int totalCpuCores, long totalMemoryMb) {
        this.totalCpuCores = totalCpuCores;
        this.totalMemoryMb = totalMemoryMb;
        reaper.scheduleAtFixedRate(this::reapSoftReservations, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized String tryReserve(String jobId, long schedulerEpoch, ResourceRequest request) {
        if (request.getCpuCores() + hardAllocatedCpu + softReservedCpu > totalCpuCores) {
            log.info("tryReserve failed for {}: requested CPU {} > available (total {}, hard {}, soft {})", 
                jobId, request.getCpuCores(), totalCpuCores, hardAllocatedCpu, softReservedCpu);
            return null;
        }
        if (request.getMemoryMb() + hardAllocatedMem + softReservedMem > totalMemoryMb) {
            log.info("tryReserve failed for {}: requested Mem {} > available (total {}, hard {}, soft {})", 
                jobId, request.getMemoryMb(), totalMemoryMb, hardAllocatedMem, softReservedMem);
            return null;
        }

        String reservationId = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + softReservationTtlMs;
        
        SoftReservation res = new SoftReservation(reservationId, schedulerEpoch, jobId, request, expiresAt);
        softReservations.put(reservationId, res);
        
        softReservedCpu += request.getCpuCores();
        softReservedMem += request.getMemoryMb();
        
        log.info("Reserved {} CPUs, {} MB for job {} (reservation {})", request.getCpuCores(), request.getMemoryMb(), jobId, reservationId);
        return reservationId;
    }

    public synchronized void commitHardAllocation(String jobId, ResourceRequest request) {
        if (hardAllocations.containsKey(jobId)) return;
        
        for (Iterator<Map.Entry<String, SoftReservation>> it = softReservations.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, SoftReservation> entry = it.next();
            if (entry.getValue().jobId().equals(jobId)) {
                softReservedCpu -= entry.getValue().request().getCpuCores();
                softReservedMem -= entry.getValue().request().getMemoryMb();
                it.remove();
            }
        }

        hardAllocations.put(jobId, request);
        hardAllocatedCpu += request.getCpuCores();
        hardAllocatedMem += request.getMemoryMb();
        log.info("Hard allocated {} CPUs, {} MB for job {}", request.getCpuCores(), request.getMemoryMb(), jobId);
    }

    public synchronized void releaseAllocation(String jobId) {
        ResourceRequest req = hardAllocations.remove(jobId);
        if (req != null) {
            hardAllocatedCpu -= req.getCpuCores();
            hardAllocatedMem -= req.getMemoryMb();
            log.info("Released hard allocation of {} CPUs, {} MB for job {}", req.getCpuCores(), req.getMemoryMb(), jobId);
        }
        
        for (Iterator<Map.Entry<String, SoftReservation>> it = softReservations.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, SoftReservation> entry = it.next();
            if (entry.getValue().jobId().equals(jobId)) {
                softReservedCpu -= entry.getValue().request().getCpuCores();
                softReservedMem -= entry.getValue().request().getMemoryMb();
                it.remove();
                log.info("Released soft reservation {} for job {}", entry.getKey(), jobId);
            }
        }
    }

    private synchronized void reapSoftReservations() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, SoftReservation>> it = softReservations.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, SoftReservation> entry = it.next();
            if (now > entry.getValue().expiresAt()) {
                SoftReservation res = entry.getValue();
                softReservedCpu -= res.request().getCpuCores();
                softReservedMem -= res.request().getMemoryMb();
                log.warn("Reservation expired: job={} resId={}", res.jobId(), res.reservationId());
                it.remove();
            }
        }
    }

    public synchronized String dumpStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("CPU Capacity: %d\n", totalCpuCores));
        sb.append(String.format("CPU Hard Allocated: %d\n", hardAllocatedCpu));
        sb.append(String.format("CPU Soft Reserved: %d\n", softReservedCpu));
        sb.append(String.format("RAM Capacity: %d MB\n", totalMemoryMb));
        sb.append(String.format("RAM Hard Allocated: %d MB\n", hardAllocatedMem));
        sb.append(String.format("RAM Soft Reserved: %d MB\n\n", softReservedMem));
        sb.append("Hard Allocations:\n");
        for (Map.Entry<String, ResourceRequest> entry : hardAllocations.entrySet()) {
            sb.append(String.format("  Job %s: %d CPU, %d MB\n", entry.getKey(), entry.getValue().getCpuCores(), entry.getValue().getMemoryMb()));
        }
        sb.append("\nSoft Reservations:\n");
        for (Map.Entry<String, SoftReservation> entry : softReservations.entrySet()) {
            sb.append(String.format("  Res %s (Job %s): %d CPU, %d MB, expires in %d ms\n", 
                entry.getKey(), entry.getValue().jobId(), entry.getValue().request().getCpuCores(), 
                entry.getValue().request().getMemoryMb(), entry.getValue().expiresAt() - System.currentTimeMillis()));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        reaper.shutdownNow();
    }
}
