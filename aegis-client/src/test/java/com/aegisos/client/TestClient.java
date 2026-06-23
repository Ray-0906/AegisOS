package com.aegisos.client;

import com.aegisos.api.dto.cluster.NodeResponse;
import com.aegisos.api.dto.file.UploadFileResponse;

import java.net.URI;
import java.util.List;
import java.util.Collections;

public class TestClient {
    public static void main(String[] args) throws Exception {
        AegisClient client = new AegisClient(Collections.singletonList(new URI("http://127.0.0.1:20001")));
        
        System.out.println("Getting nodes...");
        List<NodeResponse> nodes = client.getNodes();
        for (NodeResponse n : nodes) {
            System.out.println("Node: " + n.nodeId + " status=" + n.status + " apiPort=" + n.apiPort);
        }
        
        System.out.println("Uploading file...");
        UploadFileResponse res = client.putFile("test2.txt", "AegisOS client test".getBytes());
        System.out.println("Uploaded: " + res.path + " status=" + res.status);
        
        System.out.println("Downloading file...");
        byte[] data = client.getFile("test2.txt");
        System.out.println("Downloaded: " + new String(data));
    }
}
