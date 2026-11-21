package dev.awbridge;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS GET that does not use the network's own DNS.
 *
 * Some internet providers refuse to resolve openstreetmap.org, so the phone is
 * online but the map server "does not exist". Here the address is looked up
 * over HTTPS at a public resolver reached by IP address, and the request then
 * goes straight to that address with the real host name in the TLS handshake,
 * so the certificate is checked exactly as usual.
 *
 * Plain Java on purpose: the same file runs on a desktop JVM for testing.
 */
public final class DirectHttp {
    private DirectHttp() {
    }

    public static final class Response {
        public final int code;
        public final byte[] body;

        Response(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    /** Public resolvers that answer DNS questions as JSON over HTTPS: address, then path. */
    private static final String[][] RESOLVERS = {
        {"1.1.1.1", "/dns-query?name=%s&type=A"},
        {"8.8.8.8", "/resolve?name=%s&type=A"},
        {"1.0.0.1", "/dns-query?name=%s&type=A"},
        {"8.8.4.4", "/resolve?name=%s&type=A"},
    };
    private static final Pattern A_RECORD =
        Pattern.compile("\"type\"\\s*:\\s*1\\s*,[^{}]*?\"data\"\\s*:\\s*\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"");
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private static final Map<String, List<String>> addresses = new HashMap<>();
    private static final Map<String, Long> resolvedAt = new HashMap<>();

    /** IPv4 addresses of a host, asked from a public resolver. Cached for a few minutes. */
    public static List<String> resolve(String host, HostnameVerifier verifier) throws IOException {
        synchronized (addresses) {
            Long at = resolvedAt.get(host);
            if (at != null && System.currentTimeMillis() - at < CACHE_MS) {
                return addresses.get(host);
            }
        }
        IOException last = null;
        for (String[] resolver : RESOLVERS) {
            try {
                String path = String.format(Locale.US, resolver[1], host);
                Response response = request(resolver[0], resolver[0], path, "AWBridge/1.0",
                    "application/dns-json", verifier);
                if (response.code != 200) {
                    last = new IOException("resolver " + resolver[0] + " answered " + response.code);
                    continue;
                }
                List<String> found = new ArrayList<>();
                Matcher matcher = A_RECORD.matcher(new String(response.body, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    found.add(matcher.group(1));
                }
                if (found.isEmpty()) {
                    last = new IOException("resolver " + resolver[0] + " has no address for " + host);
                    continue;
                }
                synchronized (addresses) {
                    addresses.put(host, found);
                    resolvedAt.put(host, System.currentTimeMillis());
                }
                return found;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no resolver reachable");
    }

    /** GET https://host/path without the system DNS. Tries up to three addresses of the host. */
    public static Response get(String host, String path, String userAgent, HostnameVerifier verifier)
        throws IOException {
        List<String> ips = resolve(host, verifier);
        IOException last = null;
        int tried = 0;
        for (String ip : ips) {
            if (tried++ >= 3) break;
            try {
                return request(ip, host, path, userAgent, "*/*", verifier);
            } catch (IOException e) {
                last = e;
            }
        }
        synchronized (addresses) {
            // These addresses did not work: look the host up again next time.
            resolvedAt.remove(host);
        }
        throw last != null ? last : new IOException("no address for " + host);
    }

    /**
     * One GET to the server at [ip], presenting and verifying [host] in TLS.
     * [host] may itself be an IP address (the resolvers), which is then
     * checked against the IP entries of the certificate. [verifier] is the
     * platform's host name verifier on Android, or null on a desktop JVM.
     */
    static Response request(String ip, String host, String path, String userAgent, String accept,
                            HostnameVerifier verifier) throws IOException {
        Socket plain = new Socket();
        SSLSocket socket = null;
        try {
            // A literal address: no name lookup happens here.
            plain.connect(new InetSocketAddress(InetAddress.getByName(ip), 443), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(READ_TIMEOUT_MS);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket(plain, host, 443, true);
            if (verifier == null) {
                // Desktop JVM: the host name is checked during the handshake.
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
            }
            socket.startHandshake();
            // Android: a bare SSLSocket checks the certificate chain but not the
            // host name, so that is done here with the platform's own verifier.
            if (verifier != null && !verifier.verify(host, socket.getSession())) {
                throw new SSLPeerUnverifiedException("certificate does not match " + host);
            }

            String head = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: " + userAgent + "\r\n"
                + "Accept: " + accept + "\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            String status = readLine(in);
            String[] parts = status.split(" ");
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
                throw new IOException("unexpected answer: " + status);
            }
            int code;
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("unexpected answer: " + status);
            }
            int length = -1;
            boolean chunked = false;
            while (true) {
                String line = readLine(in);
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        length = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        length = -1;
                    }
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    String sizeLine = readLine(in);
                    int semicolon = sizeLine.indexOf(';');
                    if (semicolon >= 0) sizeLine = sizeLine.substring(0, semicolon);
                    int size;
                    try {
                        size = Integer.parseInt(sizeLine.trim(), 16);
                    } catch (NumberFormatException e) {
                        throw new IOException("bad chunk size");
                    }
                    if (size == 0) break;
                    copy(in, body, size);
                    readLine(in);
                }
            } else if (length >= 0) {
                copy(in, body, length);
            } else {
                byte[] buffer = new byte[8192];
                while (true) {
                    int n;
                    try {
                        n = in.read(buffer);
                    } catch (IOException e) {
                        // Some servers drop the connection without a TLS goodbye.
                        if (body.size() > 0) break;
                        throw e;
                    }
                    if (n < 0) break;
                    body.write(buffer, 0, n);
                    if (body.size() > MAX_BODY) throw new IOException("answer too large");
                }
            }
            return new Response(code, body.toByteArray());
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            try {
                plain.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int count) throws IOException {
        if (count > MAX_BODY || out.size() + count > MAX_BODY) throw new IOException("answer too large");
        byte[] buffer = new byte[8192];
        int left = count;
        while (left > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, left));
            if (n < 0) throw new IOException("connection closed early");
            out.write(buffer, 0, n);
            left -= n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (line.size() == 0) throw new IOException("connection closed early");
                break;
            }
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 16384) throw new IOException("header line too long");
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
