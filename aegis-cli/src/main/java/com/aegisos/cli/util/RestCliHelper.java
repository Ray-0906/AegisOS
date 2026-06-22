package com.aegisos.cli.util;

import com.aegisos.client.AegisClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class RestCliHelper {

    public static AegisClient createClient(List<String> seeds) {
        List<URI> uris = new ArrayList<>();
        for (String s : seeds) {
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                uris.add(URI.create("http://" + s));
            } else {
                uris.add(URI.create(s));
            }
        }
        return new AegisClient(uris);
    }
}
