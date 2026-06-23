package com.aegisos.client;

import com.aegisos.api.dto.cluster.LeaderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;

public class LeaderResolver {
    private static final Logger log = LoggerFactory.getLogger(LeaderResolver.class);

    private final RestTransport transport;
    private final List<URI> seeds;
    
    private volatile URI cachedLeaderUri;

    public LeaderResolver(RestTransport transport, List<URI> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("At least one seed is required");
        }
        this.transport = transport;
        this.seeds = seeds;
    }

    public URI getLeader() {
        URI current = cachedLeaderUri;
        if (current != null) {
            return current;
        }
        return discoverLeader();
    }

    public synchronized void invalidateLeader() {
        log.debug("Invalidating cached leader");
        this.cachedLeaderUri = null;
    }

    public synchronized URI discoverLeader() {
        if (cachedLeaderUri != null) {
            return cachedLeaderUri;
        }

        for (URI seed : seeds) {
            try {
                URI queryUri = seed.resolve("/v1/leader");
                LeaderResponse response = transport.get(queryUri, LeaderResponse.class);
                
                if (response.leaderId != null && !response.leaderId.isEmpty()) {
                    // Reconstruct URI using seed host and leader port
                    URI leaderUri = new URI(seed.getScheme(), null, seed.getHost(), response.apiPort, null, null, null);
                    log.info("Discovered leader: {} at {}", response.leaderId, leaderUri);
                    this.cachedLeaderUri = leaderUri;
                    return leaderUri;
                }
            } catch (Exception e) {
                log.debug("Failed to query leader from seed {}", seed, e);
            }
        }
        
        throw new IllegalStateException("Failed to discover leader from seeds: " + seeds);
    }
}
