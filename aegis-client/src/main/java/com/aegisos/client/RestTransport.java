package com.aegisos.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class RestTransport {

    private static final Logger log = LoggerFactory.getLogger(RestTransport.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    
    public RestTransport() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
    }
    
    public <T> T get(URI uri, Class<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        
        return execute(request, responseType);
    }
    
    public <T> T post(URI uri, Object body, Class<T> responseType) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();
                
        return execute(request, responseType);
    }
    
    public <T> T delete(URI uri, Class<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();
                
        return execute(request, responseType);
    }
    
    public <T> T putBinary(URI uri, byte[] data, Class<T> responseType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .timeout(Duration.ofSeconds(30))
                .build();
                
        return execute(request, responseType);
    }
    
    public byte[] getBinary(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
                
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            throw new ApiException(response.statusCode(), new String(response.body()));
        }
    }
    
    private <T> T execute(HttpRequest request, Class<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return mapper.readValue(response.body(), responseType);
        } else if (response.statusCode() == 503) {
            try {
                com.aegisos.api.dto.common.ErrorResponse errorRes = mapper.readValue(response.body(), com.aegisos.api.dto.common.ErrorResponse.class);
                if ("NOT_LEADER".equals(errorRes.error)) {
                    throw new NotLeaderException(errorRes.leaderId, errorRes.apiPort);
                }
                throw new ApiException(response.statusCode(), errorRes.error);
            } catch (Exception e) {
                if (e instanceof NotLeaderException) {
                    throw e;
                }
                throw new ApiException(response.statusCode(), response.body());
            }
        } else {
            try {
                com.aegisos.api.dto.common.ErrorResponse errorRes = mapper.readValue(response.body(), com.aegisos.api.dto.common.ErrorResponse.class);
                throw new ApiException(response.statusCode(), errorRes.error);
            } catch (Exception e) {
                if (e instanceof ApiException) throw e;
                throw new ApiException(response.statusCode(), response.body());
            }
        }
    }
}
