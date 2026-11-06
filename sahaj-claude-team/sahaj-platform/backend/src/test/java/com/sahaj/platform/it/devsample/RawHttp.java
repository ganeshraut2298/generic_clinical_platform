package com.sahaj.platform.it.devsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A minimal HTTP/1.1 client over a plain socket for the DEC-016 tests. The JDK client refuses to send a
 * {@code Host} header of our choosing and normalises header names; these tests need exact control (a repeated header,
 * an upper-case name, a hostile {@code Host}, a percent-encoded path), so the request text is written by hand and the
 * answer is read until the server closes the connection. Test code only; it connects to 127.0.0.1.
 */
public final class RawHttp {

    private RawHttp() {
    }

    /** One request. Headers are written in the order given, with the exact case given. */
    public static final class Request {
        private final int port;
        private final String method;
        private final String target;
        private final List<String[]> headers = new ArrayList<>();
        private byte[] body = new byte[0];
        private boolean defaultHost = true;

        Request(int port, String method, String target) {
            this.port = port;
            this.method = method;
            this.target = target;
        }

        /** Adds a header. Calling it twice with the same name sends the header twice. */
        public Request header(String name, String value) {
            if (name.equalsIgnoreCase("Host")) {
                defaultHost = false;
            }
            headers.add(new String[] {name, value});
            return this;
        }

        public Request noHost() {
            defaultHost = false;
            return this;
        }

        public Request json(String text) {
            this.body = text.getBytes(StandardCharsets.UTF_8);
            return header("Content-Type", "application/json");
        }

        public Response send() throws IOException {
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            if (defaultHost) {
                head.append("Host: 127.0.0.1:").append(port).append("\r\n");
            }
            for (String[] header : headers) {
                head.append(header[0]).append(": ").append(header[1]).append("\r\n");
            }
            if (body.length > 0 || "POST".equals(method)) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(30000);
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
                return parse(readAll(socket.getInputStream()));
            }
        }
    }

    public static Request request(int port, String method, String target) {
        return new Request(port, method, target);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            all.write(buffer, 0, read);
        }
        return all.toByteArray();
    }

    private static Response parse(byte[] raw) {
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        assertThat(split).as("a complete HTTP response head").isGreaterThan(0);
        String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        List<String[]> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.add(new String[] {lines[i].substring(0, colon), lines[i].substring(colon + 1).trim()});
        }
        byte[] rest = new byte[raw.length - split - 4];
        System.arraycopy(raw, split + 4, rest, 0, rest.length);
        boolean chunked = headers.stream()
                .anyMatch(h -> h[0].equalsIgnoreCase("Transfer-Encoding") && h[1].toLowerCase(Locale.ROOT).contains("chunked"));
        return new Response(status, headers, chunked ? unchunk(rest) : rest);
    }

    private static byte[] unchunk(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        byte[] crlf = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        while (pos < data.length) {
            int end = indexOf(data, crlf, pos);
            if (end < 0) {
                break;
            }
            int size = Integer.parseInt(new String(data, pos, end - pos, StandardCharsets.ISO_8859_1).trim().split(";")[0], 16);
            if (size == 0) {
                break;
            }
            out.write(data, end + 2, size);
            pos = end + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** A parsed answer. */
    public record Response(int status, List<String[]> headers, byte[] body) {

        public String header(String name) {
            return headers.stream().filter(h -> h[0].equalsIgnoreCase(name)).map(h -> h[1]).findFirst().orElse(null);
        }

        public boolean hasHeader(String name) {
            return header(name) != null;
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /**
         * Everything that must be byte-identical between two "not found" answers: status, the headers except the ones
         * that legitimately differ per answer (Date, and the per-request id, connection handling), and the body.
         */
        public String fingerprint() {
            String headerText = headers.stream()
                    .filter(h -> !List.of("date", "connection", "keep-alive", "x-request-id").contains(h[0].toLowerCase(Locale.ROOT)))
                    .map(h -> h[0].toLowerCase(Locale.ROOT) + "=" + h[1])
                    .sorted()
                    .collect(Collectors.joining("|"));
            return status + "|" + headerText + "|body=" + bodyText();
        }

        /** The contract's DevNotFound answer (ADR-008 D3): 404, empty body, no content type, no store, nothing else. */
        public Response assertDevNotFound() {
            assertThat(status).as("status").isEqualTo(404);
            assertThat(body).as("body").isEmpty();
            assertThat(hasHeader("Content-Type")).as("no Content-Type").isFalse();
            assertThat(header("Cache-Control")).as("Cache-Control").isEqualTo("no-store");
            assertThat(hasHeader("WWW-Authenticate")).as("no WWW-Authenticate").isFalse();
            assertThat(headers.stream().noneMatch(h -> h[0].toLowerCase(Locale.ROOT).startsWith("access-control-")))
                    .as("no CORS headers").isTrue();
            return this;
        }
    }
}
