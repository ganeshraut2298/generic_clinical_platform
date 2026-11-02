package com.sahaj.platform.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Sends real HTTP requests to the embedded server of a RANDOM_PORT test, from this machine's loopback address. */
final class RealHttpSupport {

    static final String PATH = "/api/v1/platform/public/sales-enquiries";

    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    RealHttpSupport(int port) {
        this.port = port;
    }

    /** Posts a JSON body, with optional extra headers given as name, value, name, value ... */
    HttpResponse<String> post(String json, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String body(String name, String mobile) {
        return "{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\",\"source\":\"CONTACT_SALES\"}";
    }
}
