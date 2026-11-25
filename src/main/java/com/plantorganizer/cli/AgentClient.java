package com.plantorganizer.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * The CLI's only way into the system: a client for the Plant Organizer REST
 * API (the same endpoints the GUI uses). It stands in for the server's
 * AgentController on this side of the network (Proxy pattern), so commands
 * call plain methods and never deal with HTTP, headers or JSON parsing.
 *
 * Every request carries the current user in the {@code X-User-Id} header.
 * Error answers become {@link ApiException} with the server's message; no
 * answer at all becomes {@link ServerUnavailableException}.
 */
public class AgentClient {

    /** Same limit as the server's PlantIdentifier.MAX_IMAGES. */
    public static final int MAX_PHOTOS = 5;
    /** Same limit as the server's spring.servlet.multipart.max-file-size. */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final String USER_HEADER = "X-User-Id";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** AI steps (identification, health checks, recommendations) can take a while. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final String baseUrl;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private long userId;

    public AgentClient(String baseUrl, long userId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.userId = userId;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public long userId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    // ------------------------------------------------------------------ requests

    public JsonNode get(String path) {
        return send(request(path).GET());
    }

    public JsonNode get(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(path);
        char sep = '?';
        for (var e : query.entrySet()) {
            sb.append(sep).append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            sep = '&';
        }
        return get(sb.toString());
    }

    /** POST with a JSON body ({@code body} may be null for an empty POST). */
    public JsonNode post(String path, Object body) {
        return send(withJson(request(path), "POST", body));
    }

    public JsonNode put(String path, Object body) {
        return send(withJson(request(path), "PUT", body));
    }

    public JsonNode delete(String path) {
        return send(request(path).DELETE());
    }

    /** POST multipart/form-data, as an HTML form with file inputs would send it. */
    public JsonNode postMultipart(String path, List<Part> parts) {
        String boundary = "----PlantOrganizerCli" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            write(body, "--" + boundary + "\r\n");
            if (part.file() == null) {
                write(body, "Content-Disposition: form-data; name=\"" + part.name() + "\"\r\n");
                write(body, "Content-Type: text/plain; charset=UTF-8\r\n\r\n");
                write(body, part.text());
            } else {
                String filename = part.file().getFileName().toString().replace("\"", "");
                write(body, "Content-Disposition: form-data; name=\"" + part.name() + "\"; filename=\"" + filename + "\"\r\n");
                write(body, "Content-Type: " + contentType(part.file()) + "\r\n\r\n");
                body.writeBytes(readFile(part.file()));
            }
            write(body, "\r\n");
        }
        write(body, "--" + boundary + "--\r\n");
        return send(request(path)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    /** One field of a multipart form: either text or a file. */
    public record Part(String name, String text, Path file) {

        public static Part text(String name, String value) {
            return new Part(name, value, null);
        }

        public static Part file(String name, Path file) {
            return new Part(name, null, file);
        }
    }

    /**
     * Checks a file before uploading it, so the user gets a clear message
     * instead of a server error.
     *
     * @throws CliException when it is missing, unreadable, a folder or too large
     */
    public static Path checkUpload(String file) {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            throw new CliException("No file found at " + file + ".");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new CliException(file + " is not a readable file.");
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new CliException(file + " is larger than 20 MB. Use a smaller photo.");
            }
        } catch (IOException e) {
            throw new CliException("Could not read " + file + ".");
        }
        return path;
    }

    // ------------------------------------------------------------------ plumbing

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header(USER_HEADER, String.valueOf(userId))
                .header("Accept", "application/json");
    }

    private HttpRequest.Builder withJson(HttpRequest.Builder builder, String method, Object body) {
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
    }

    private JsonNode send(HttpRequest.Builder builder) {
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new ServerUnavailableException(baseUrl);
        } catch (HttpTimeoutException e) {
            throw new ApiException(0, "The server took too long to answer. Please try again in a moment.", true);
        } catch (IOException e) {
            throw new ServerUnavailableException(baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Cancelled.");
        }
        JsonNode body = parse(response.body());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return body;
        }
        throw error(status, body);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return MissingNode.getInstance();
        }
    }

    /** The server's ApiError {"error", "service", "retryable"}, or Spring's default error body. */
    private static ApiException error(int status, JsonNode body) {
        String message = Json.str(body, "error");
        if (body.has("retryable")) {
            return new ApiException(status, message != null ? message : "The server reported an error.",
                    body.path("retryable").asBoolean());
        }
        if (status == 404) {
            return new ApiException(status, "The server doesn't know this request (" + Json.str(body, "path")
                    + "). Is it an up-to-date Plant Organizer server?", false);
        }
        return new ApiException(status, "The server reported an error (" + status
                + (message != null ? " " + message : "") + ").", status >= 500);
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "heic" -> "image/heic";
            case "ics" -> "text/calendar";
            default -> {
                try {
                    String probed = Files.probeContentType(file);
                    yield probed != null ? probed : "application/octet-stream";
                } catch (IOException e) {
                    yield "application/octet-stream";
                }
            }
        };
    }

    private static byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CliException("Could not read " + file + ".");
        }
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
