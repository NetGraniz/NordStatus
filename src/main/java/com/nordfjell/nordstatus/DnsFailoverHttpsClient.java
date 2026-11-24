package com.nordfjell.nordstatus;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DnsFailoverHttpsClient {
    private static final int MAX_STATUS_LINE_BYTES = 8_192;

    private final SSLSocketFactory socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
    private volatile String preferredAddress;

    Response get(URI uri, Duration timeout, String userAgent) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Only HTTPS URLs with a host are supported");
        }

        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        int timeoutMillis = Math.toIntExact(Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        List<InetAddress> addresses = resolveIpv4(host);
        IOException failure = null;

        for (InetAddress address : addresses) {
            try {
                int status = request(uri, host, port, address, timeoutMillis, userAgent);
                preferredAddress = address.getHostAddress();
                return new Response(status, preferredAddress);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = new IOException("Could not connect to any address for " + host);
                }
                failure.addSuppressed(new IOException(address.getHostAddress() + ": "
                        + describe(exception), exception));
            }
        }

        throw failure == null ? new IOException("DNS returned no usable IPv4 addresses for " + host) : failure;
    }

    private List<InetAddress> resolveIpv4(String host) throws IOException {
        Map<String, InetAddress> unique = new LinkedHashMap<>();
        Arrays.stream(InetAddress.getAllByName(host))
                .filter(Inet4Address.class::isInstance)
                .forEach(address -> unique.putIfAbsent(address.getHostAddress(), address));

        List<InetAddress> addresses = new ArrayList<>(unique.values());
        String preferred = preferredAddress;
        if (preferred != null) {
            addresses.sort(Comparator.comparing(address -> !preferred.equals(address.getHostAddress())));
        }
        return addresses;
    }

    private int request(URI uri, String host, int port, InetAddress address,
                        int timeoutMillis, String userAgent) throws IOException {
        Socket plainSocket = new Socket();
        try {
            plainSocket.connect(new InetSocketAddress(address, port), timeoutMillis);
            plainSocket.setSoTimeout(timeoutMillis);

            try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(plainSocket, host, port, true)) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                socket.setSSLParameters(parameters);
                socket.setSoTimeout(timeoutMillis);
                socket.startHandshake();

                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                writer.write("GET " + requestTarget(uri) + " HTTP/1.1\r\n");
                writer.write("Host: " + hostHeader(host, uri.getPort()) + "\r\n");
                writer.write("User-Agent: " + userAgent + "\r\n");
                writer.write("Accept: */*\r\n");
                writer.write("Connection: close\r\n\r\n");
                writer.flush();

                String statusLine = readLine(new BufferedInputStream(socket.getInputStream()));
                return parseStatus(statusLine);
            }
        } catch (IOException | RuntimeException exception) {
            try {
                plainSocket.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private static String requestTarget(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String hostHeader(String host, int explicitPort) {
        return explicitPort == -1 || explicitPort == 443 ? host : host + ":" + explicitPort;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        for (int index = 0; index < MAX_STATUS_LINE_BYTES; index++) {
            int current = input.read();
            if (current == -1) {
                break;
            }
            if (previous == '\r' && current == '\n') {
                line.setLength(Math.max(0, line.length() - 1));
                return line.toString();
            }
            line.append((char) current);
            previous = current;
        }
        throw new IOException("Invalid or oversized HTTP status line");
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Invalid HTTP status line");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid HTTP status code", exception);
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    record Response(int statusCode, String address) {
    }
}
