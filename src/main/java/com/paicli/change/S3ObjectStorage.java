package com.paicli.change;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Small path-style S3-compatible adapter, including SigV4 and immutable PUT reconciliation. */
public final class S3ObjectStorage implements ObjectStorage {
    private static final byte[] EMPTY = new byte[0];
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final OkHttpClient client;
    private final Clock clock;

    public S3ObjectStorage(String endpoint, String bucket, String region, String accessKey, String secretKey) {
        this(endpoint, bucket, region, accessKey, secretKey, Clock.systemUTC());
    }

    S3ObjectStorage(String endpoint, String bucket, String region, String accessKey, String secretKey, Clock clock) {
        this.endpoint = URI.create(text(endpoint, "S3 endpoint"));
        if (!List.of("http", "https").contains(this.endpoint.getScheme()) || this.endpoint.getHost() == null
                || this.endpoint.getUserInfo() != null || this.endpoint.getQuery() != null)
            throw new IllegalArgumentException("S3 endpoint 必须是不含凭据和 query 的 HTTP(S) URL");
        this.bucket = segment(bucket, "S3 bucket");
        this.region = text(region, "S3 region");
        this.accessKey = text(accessKey, "S3 access key");
        this.secretKey = text(secretKey, "S3 secret key");
        this.clock = java.util.Objects.requireNonNull(clock);
        this.client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build();
    }

    @Override public void putIfAbsent(String key, byte[] content, String sha256) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("if-none-match", "*");
        headers.put("x-amz-meta-sha256", sha256);
        try (Response response = execute("PUT", key, Map.of(), headers, content)) {
            if (response.isSuccessful()) return;
            if (response.code() == 409 || response.code() == 412) {
                Metadata existing = metadata(key).orElseThrow(() -> new IOException("S3 条件写入冲突但对象不可读"));
                if (existing.sizeBytes() == content.length && sha256.equals(existing.sha256())) return;
                throw new ChangeConflictException("同一对象 key 已有不同内容: " + key);
            }
            throw failure("PUT", key, response);
        }
    }

    @Override public byte[] get(String key, long maxBytes) throws IOException {
        try (Response response = execute("GET", key, Map.of(), Map.of(), EMPTY)) {
            if (response.code() == 404) throw new IOException("S3 对象不存在: " + key);
            if (!response.isSuccessful()) throw failure("GET", key, response);
            long declared = response.body() == null ? 0 : response.body().contentLength();
            if (declared > maxBytes) throw new IOException("S3 对象超过读取上限: " + key);
            byte[] bytes = response.body() == null ? EMPTY : response.body().bytes();
            if (bytes.length > maxBytes) throw new IOException("S3 对象超过读取上限: " + key);
            return bytes;
        }
    }

    @Override public Optional<Metadata> metadata(String key) throws IOException {
        try (Response response = execute("HEAD", key, Map.of(), Map.of(), EMPTY)) {
            if (response.code() == 404) return Optional.empty();
            if (!response.isSuccessful()) throw failure("HEAD", key, response);
            String sha = response.header("x-amz-meta-sha256", "").trim();
            if (sha.isEmpty()) throw new IOException("S3 对象缺少 x-amz-meta-sha256: " + key);
            return Optional.of(new Metadata(Long.parseLong(response.header("Content-Length", "0")), sha));
        }
    }

    @Override public List<String> list(String prefix) throws IOException {
        Map<String, String> query = Map.of("list-type", "2", "prefix", prefix, "max-keys", "1000");
        try (Response response = execute("GET", "", query, Map.of(), EMPTY)) {
            if (!response.isSuccessful()) throw failure("LIST", prefix, response);
            byte[] xml = response.body() == null ? EMPTY : response.body().bytes();
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                var nodes = document.getElementsByTagName("Key");
                List<String> result = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent());
                if ("true".equalsIgnoreCase(document.getDocumentElement().getElementsByTagName("IsTruncated")
                        .item(0).getTextContent())) throw new IOException("S3 Evidence prefix 超过 1000 对象上限");
                return List.copyOf(result);
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("S3 LIST XML 无效", e); }
        }
    }

    @Override public void checkHealth() {
        try { list(".paichange-health/"); }
        catch (IOException e) { throw new IllegalStateException("S3 对象存储健康检查失败: " + e.getMessage(), e); }
    }

    private Response execute(String method, String key, Map<String, String> query,
                             Map<String, String> extraHeaders, byte[] body) throws IOException {
        String path = canonicalPath(key);
        String canonicalQuery = query.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String url = endpoint.toString().replaceAll("/$", "") + path + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        String timestamp = AMZ_DATE.format(clock.instant());
        String day = DAY.format(clock.instant());
        String payloadHash = sha256(body);
        String host = endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        Map<String, String> headers = new java.util.TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", timestamp);
        extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.trim()));
        String canonicalHeaders = headers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .reduce("", String::concat);
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String scope = day + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day), region), "s3"), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        Request.Builder request = new Request.Builder().url(url).header("Authorization", authorization);
        headers.forEach(request::header);
        RequestBody requestBody = RequestBody.create(body, MediaType.parse("application/octet-stream"));
        request.method(method, (method.equals("PUT") || method.equals("POST")) ? requestBody : null);
        return client.newCall(request.build()).execute();
    }

    private String canonicalPath(String key) {
        String base = endpoint.getPath() == null ? "" : endpoint.getPath().replaceAll("/$", "");
        StringBuilder path = new StringBuilder(base).append('/').append(encode(bucket));
        if (key != null && !key.isEmpty()) {
            for (String part : key.split("/", -1)) path.append('/').append(encode(segment(part, "object key")));
        }
        return path.toString();
    }

    private static IOException failure(String operation, String key, Response response) throws IOException {
        String requestId = response.header("x-amz-request-id", "unknown");
        if (response.body() != null) response.body().close();
        return new IOException("S3 " + operation + " 失败 status=" + response.code() + " requestId=" + requestId + " key=" + key);
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value.trim();
    }
    private static String segment(String value, String name) {
        String result = text(value, name);
        if (result.contains("/") || result.equals(".") || result.equals("..")) throw new IllegalArgumentException("非法 " + name);
        return result;
    }
}
