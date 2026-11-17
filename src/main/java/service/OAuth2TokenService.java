package service;

import util.CryptoUtil;
import util.MiniJson;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * OAuth2 "device code" client for Microsoft Entra ID (Azure AD).
 *
 * Exchange Online has disabled Basic Authentication tenant-wide for years,
 * and many tenants additionally block legacy protocols (IMAP/POP/SMTP AUTH)
 * entirely via Conditional Access — even with OAuth2. This service exists to
 * get an OAuth2 access token for Microsoft Graph (which is unaffected by the
 * legacy-protocol block, since it's the same HTTPS path the Outlook web/
 * mobile apps use) without requiring a tenant admin to do anything, by using
 * a self-registered "public client" Azure AD app + the device code grant.
 *
 * <h2>One-time setup (per mailbox)</h2>
 * Call {@link #enroll} once, interactively. It prints a URL + short code;
 * a person opens the URL in any browser, signs in normally (MFA included),
 * and enters the code. Microsoft then returns a refresh token, which is
 * encrypted at rest (via {@link CryptoUtil}, the same machine-local key
 * already used for stored credential passwords) and cached to disk.
 *
 * <h2>Every scheduled run</h2>
 * {@link #getValidAccessToken} silently exchanges the cached refresh token
 * for a new access token over HTTPS — no browser, no human. Microsoft may
 * rotate the refresh token on each call; the rotated one is re-cached.
 */
public class OAuth2TokenService {

    private static final String DEFAULT_AUTH_BASE = "https://login.microsoftonline.com/";
    private String AUTH_BASE = DEFAULT_AUTH_BASE; // overridable for tests only (see setAuthBaseForTesting)
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15)).build();
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(30);
    private final Path tokenDir;

    /**
     * @deprecated stores tokens under the current OS user's home directory
     * ({@code user.home}), which differs between the GUI (runs as the logged-
     * in user) and the Daemon (runs as SYSTEM per app-config.xml's
     * {@code <runAsSystem>}) — so a mailbox authorized via the GUI was
     * invisible to the Daemon and vice versa. Whichever process happened to
     * poll a due task first determined success/failure, producing exactly
     * "fails once, immediately succeeds on the next run" when both are
     * running. Use {@link #OAuth2TokenService(Path)} with
     * {@link #sharedTokenDir} (the app's shared dataDir) instead, so every
     * process — regardless of which account runs it — reads/writes the same
     * token files.
     */
    @Deprecated
    public OAuth2TokenService() {
        this(Paths.get(System.getProperty("user.home"), ".opstool", "oauth"));
    }

    public OAuth2TokenService(Path tokenDir) {
        this.tokenDir = tokenDir;
        if (tokenDir != null && tokenDir.getParent() != null) {
            CryptoUtil.configure(tokenDir.getParent().toFile()); // key file lives in <dataDir>
        }
        try { Files.createDirectories(tokenDir); } catch (IOException ignored) {}
    }

    /** Token storage location shared by every process (GUI, Daemon) regardless of which OS account runs it. */
    public static Path sharedTokenDir(File dataDir) {
        return dataDir.toPath().resolve("oauth");
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    /** True if a cached (encrypted) refresh token exists for this account key. */
    public boolean isEnrolled(String accountKey) {
        return Files.exists(tokenFile(accountKey));
    }

    /**
     * Interactive one-time enrollment via the OAuth2 device code grant.
     *
     * @param accountKey  local identifier for this mailbox (typically its email address)
     * @param tenantId    Azure AD tenant ID, or "common" for any org/personal account
     * @param clientId    the Application (client) ID of the self-registered Azure AD app
     * @param scope       space-separated scopes, e.g. "https://graph.microsoft.com/Mail.Read offline_access"
     * @param onPrompt    called with the human-readable "open this URL and enter this code" message
     */
    public void enroll(String accountKey, String tenantId, String clientId, String scope,
                        Consumer<String> onPrompt) throws IOException, InterruptedException {

        HttpRequest deviceReq = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_BASE + tenantId + "/oauth2/v2.0/devicecode"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "client_id=" + urlEnc(clientId) + "&scope=" + urlEnc(scope)))
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> deviceResp = http.send(deviceReq, HttpResponse.BodyHandlers.ofString());
        if (deviceResp.statusCode() != 200) {
            throw new IOException("Device code request failed (" + deviceResp.statusCode()
                    + "): " + deviceResp.body());
        }
        Map<String, Object> dc = MiniJson.parseObject(deviceResp.body());

        String message = MiniJson.getString(dc, "message", null);
        if (message == null) {
            // Fall back to constructing a message from the individual fields
            message = "To sign in, open " + MiniJson.getString(dc, "verification_uri", "https://microsoft.com/devicelogin")
                    + " and enter the code " + MiniJson.getString(dc, "user_code", "?");
        }
        onPrompt.accept(message);

        String deviceCode = MiniJson.getString(dc, "device_code", null);
        if (deviceCode == null) throw new IOException("Device code response missing device_code: " + deviceResp.body());

        int interval  = MiniJson.getInt(dc, "interval", 5);
        int expiresIn = MiniJson.getInt(dc, "expires_in", 900);
        long deadline = System.currentTimeMillis() + expiresIn * 1000L;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);

            HttpRequest pollReq = HttpRequest.newBuilder()
                    .uri(URI.create(AUTH_BASE + tenantId + "/oauth2/v2.0/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                                    + "&client_id=" + urlEnc(clientId)
                                    + "&device_code=" + urlEnc(deviceCode)))
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> pollResp = http.send(pollReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = MiniJson.parseObject(pollResp.body());

            if (pollResp.statusCode() == 200) {
                saveRefreshToken(accountKey, MiniJson.getString(body, "refresh_token", null));
                return;
            }

            String error = MiniJson.getString(body, "error", "");
            if (error.equals("slow_down")) {
                interval += 5; // the server asked us to poll less often (RFC 8628 section 3.5)
                continue;
            }
            if (error.equals("authorization_pending")) {
                continue; // keep polling
            }
            throw new IOException("Device code sign-in failed: "
                    + MiniJson.getString(body, "error_description", error));
        }
        throw new IOException("Device code expired before sign-in completed. Please retry enrollment.");
    }

    /**
     * Silently exchanges the cached refresh token for a fresh access token.
     * Intended for unattended/scheduled use — throws if no enrollment exists yet.
     *
     * Caches the access token in memory until shortly before it expires, so
     * repeated calls in quick succession (e.g. a task polling every 30s)
     * don't hit the refresh-token endpoint every time. This also shrinks the
     * window for a cross-process race: the GUI's in-app scheduler and the
     * standalone Daemon are both designed to be able to run at the same time
     * against the same tasks (see Daemon.java), and Microsoft commonly
     * rotates the refresh token on each exchange — invalidating the previous
     * one. If two overlapping calls read the same refresh token and submit
     * it concurrently, the loser gets an invalid_grant/401 even though the
     * winner succeeded and already rotated the token on disk (explaining a
     * run failing with "not authorized" while the very next run — which
     * picks up the winner's newly-rotated token — works fine). The file lock
     * below serializes the whole read-refresh-write sequence across
     * processes so only one refresh happens at a time per account.
     */
    public String getValidAccessToken(String accountKey, String tenantId, String clientId, String scope)
            throws IOException {

        CachedToken cached = accessTokenCache.get(accountKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtEpochMs) {
            return cached.accessToken;
        }

        Path lockFile = tokenDir.resolve(safeName(accountKey) + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock fileLock = channel.lock()) {

            // Re-check after acquiring the lock — another thread/process may
            // have just refreshed while we were waiting for it.
            cached = accessTokenCache.get(accountKey);
            if (cached != null && System.currentTimeMillis() < cached.expiresAtEpochMs) {
                return cached.accessToken;
            }

            // Distinguish "genuinely never enrolled" from "token file exists
            // but couldn't be read" — these used to be silently collapsed
            // into the same null/"not authorized yet" outcome, which made it
            // impossible to tell from the logs whether enrollment was real
            // or something else (wrong data directory, corrupted file, a
            // read racing a concurrent write, permissions) was going on.
            Path resolvedTokenFile = tokenFile(accountKey);
            boolean fileExists = Files.exists(resolvedTokenFile);
            String refreshToken = fileExists ? loadRefreshToken(accountKey) : null;

            if (refreshToken == null) {
                if (!fileExists) {
                    throw new IOException("Mailbox '" + accountKey + "' is not authorized yet "
                            + "(no token file at: " + resolvedTokenFile.toAbsolutePath() + "). "
                            + "Use the \"Authorize Mailbox\" button in the task editor to complete one-time sign-in.");
                } else {
                    long size = 0L;
                    String mtime = "unknown";
                    try {
                        size = Files.size(resolvedTokenFile);
                        mtime = java.time.Instant.ofEpochMilli(Files.getLastModifiedTime(resolvedTokenFile).toMillis()).toString();
                    } catch (IOException ignored) {}
                    throw new IOException("Mailbox '" + accountKey + "' has a token file at "
                            + resolvedTokenFile.toAbsolutePath() + " (size=" + size + " bytes, last modified " + mtime
                            + ") but it could not be decrypted/parsed — it may be corrupted, from a different "
                            + "machine-local encryption key, or was read mid-write. Re-run \"Authorize Mailbox\" "
                            + "to re-enroll if this persists.");
                }
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AUTH_BASE + tenantId + "/oauth2/v2.0/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "grant_type=refresh_token"
                                    + "&client_id=" + urlEnc(clientId)
                                    + "&refresh_token=" + urlEnc(refreshToken)
                                    + "&scope=" + urlEnc(scope)))
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> resp;
            long t0 = System.currentTimeMillis();
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while refreshing OAuth2 token", ie);
            } catch (IOException ioe) {
                GraphCallLog.record("token", "POST", req.uri(), accountKey, 0, System.currentTimeMillis() - t0, -1, null, 1,
                        "Refresh token grant: " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
                throw ioe;
            }
            GraphCallLog.record("token", "POST", req.uri(), accountKey, resp.statusCode(), System.currentTimeMillis() - t0, -1,
                    resp.headers().firstValue("request-id").orElse(null), 1,
                    resp.statusCode() == 200 ? "Refresh token grant" : "Refresh token grant: " + GraphCallLog.errorText(resp.body()));

            Map<String, Object> body = MiniJson.parseObject(resp.body());
            if (resp.statusCode() != 200) {
                throw new IOException("Token refresh failed for '" + accountKey + "': "
                        + MiniJson.getString(body, "error_description", resp.body())
                        + " — the cached refresh token may be expired or revoked; re-run \"Authorize Mailbox\".");
            }

            String accessToken = MiniJson.getString(body, "access_token", null);
            if (accessToken == null) throw new IOException("Token response missing access_token: " + resp.body());

            // Microsoft frequently rotates the refresh token — re-cache it if a new one came back.
            String newRefresh = MiniJson.getString(body, "refresh_token", null);
            if (newRefresh != null) saveRefreshToken(accountKey, newRefresh);

            int expiresIn = MiniJson.getInt(body, "expires_in", 3600);
            // Refresh a little early (60s buffer) rather than cutting it exactly at expiry.
            long expiresAt = System.currentTimeMillis() + Math.max(0, expiresIn - 60) * 1000L;
            accessTokenCache.put(accountKey, new CachedToken(accessToken, expiresAt));

            return accessToken;
        }
    }


    // ─── App-only login with a client certificate (client-credentials flow) ─────────────────
    //
    // No user, no refresh token, nothing that rotates: the app registration proves itself with a
    // short-lived JWT signed by the certificate's private key (RFC 7523 / Microsoft "client
    // assertion"), and receives an access token for https://graph.microsoft.com/.default.
    // The registration needs *application* permissions (e.g. Mail.ReadWrite) with admin consent;
    // mailboxes are addressed as /users/{mailbox} (see GraphMailService#forMailbox).

    private final Map<String, CachedToken> appOnlyCache = new ConcurrentHashMap<>();

    /** For tests: point the token endpoint at a local fake. */
    void setAuthBaseForTesting(String base) { this.AUTH_BASE = base.endsWith("/") ? base : base + "/"; }

    public String getAppOnlyAccessToken(String tenantId, String clientId, Path pfxFile, char[] pfxPassword,
                                        String scope) throws IOException {
        String cacheKey = tenantId + "|" + clientId + "|" + pfxFile.toAbsolutePath() + "|" + scope;
        CachedToken cached = appOnlyCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtEpochMs) return cached.accessToken;

        String tokenUrl = AUTH_BASE + tenantId + "/oauth2/v2.0/token";
        String assertion = buildClientAssertion(clientId, tokenUrl, pfxFile, pfxPassword);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials"
                                + "&client_id=" + urlEnc(clientId)
                                + "&scope=" + urlEnc(scope)
                                + "&client_assertion_type=" + urlEnc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                                + "&client_assertion=" + urlEnc(assertion)))
                .build();
        HttpResponse<String> resp;
        long t0 = System.currentTimeMillis();
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting an app-only token", ie);
        } catch (IOException ioe) {
            GraphCallLog.record("token", "POST", req.uri(), clientId, 0, System.currentTimeMillis() - t0, -1, null, 1,
                    "App-only (certificate) token: " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
            throw ioe;
        }
        GraphCallLog.record("token", "POST", req.uri(), clientId, resp.statusCode(), System.currentTimeMillis() - t0, -1,
                resp.headers().firstValue("request-id").orElse(null), 1,
                resp.statusCode() == 200 ? "App-only (certificate) token" : "App-only (certificate) token: " + GraphCallLog.errorText(resp.body()));
        Map<String, Object> body = MiniJson.parseObject(resp.body());
        if (resp.statusCode() != 200) {
            throw new IOException("App-only token request failed (" + resp.statusCode() + "): "
                    + MiniJson.getString(body, "error_description", resp.body()));
        }
        String token = MiniJson.getString(body, "access_token", null);
        if (token == null) throw new IOException("Token response had no access_token");
        int expiresIn = MiniJson.getInt(body, "expires_in", 3600);
        appOnlyCache.put(cacheKey, new CachedToken(token, System.currentTimeMillis() + Math.max(60, expiresIn - 120) * 1000L));
        return token;
    }

    /** Signed JWT proving possession of the certificate's private key. Package-private for tests. */
    static String buildClientAssertion(String clientId, String audience, Path pfxFile, char[] pfxPassword)
            throws IOException {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = Files.newInputStream(pfxFile)) {
                ks.load(in, pfxPassword);
            }
            java.security.PrivateKey key = null;
            java.security.cert.X509Certificate cert = null;
            for (java.util.Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                String alias = e.nextElement();
                if (ks.isKeyEntry(alias)) {
                    key = (java.security.PrivateKey) ks.getKey(alias, pfxPassword);
                    cert = (java.security.cert.X509Certificate) ks.getCertificate(alias);
                    break;
                }
            }
            if (key == null || cert == null) throw new IOException("No private key + certificate found in " + pfxFile);

            java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
            String thumb = b64.encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            long now = System.currentTimeMillis() / 1000;
            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"x5t#S256\":\"" + thumb + "\"}";
            String claims = "{\"aud\":\"" + audience + "\",\"iss\":\"" + clientId + "\",\"sub\":\"" + clientId
                    + "\",\"jti\":\"" + java.util.UUID.randomUUID() + "\",\"nbf\":" + (now - 30)
                    + ",\"exp\":" + (now + 600) + "}";
            String signingInput = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                    + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64.encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IOException("Could not build the client assertion from " + pfxFile + ": " + e.getMessage(), e);
        }
    }

    private static class CachedToken {
        final String accessToken;
        final long expiresAtEpochMs;
        CachedToken(String accessToken, long expiresAtEpochMs) {
            this.accessToken = accessToken;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    private final Map<String, CachedToken> accessTokenCache = new ConcurrentHashMap<>();

    /** Removes the cached authorization for this mailbox (forces re-enrollment). */
    public void revoke(String accountKey) {
        try { Files.deleteIfExists(tokenFile(accountKey)); } catch (IOException ignored) {}
    }

    // ─── Cache persistence (encrypted at rest via CryptoUtil) ────────────────

    private void saveRefreshToken(String accountKey, String refreshToken) throws IOException {
        if (refreshToken == null) return; // not rotated this call — keep whatever is already cached
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("refresh_token", refreshToken);
        fields.put("saved_at", Instant.now().toString());
        String json = MiniJson.writeObject(fields);
        String encrypted = CryptoUtil.encrypt(json);

        Path f = tokenFile(accountKey);
        Files.write(f, encrypted.getBytes(StandardCharsets.UTF_8));
        try {
            f.toFile().setReadable(false, false);
            f.toFile().setReadable(true, true);
            f.toFile().setWritable(false, false);
            f.toFile().setWritable(true, true);
        } catch (Exception ignored) {}
    }

    private String loadRefreshToken(String accountKey) {
        Path f = tokenFile(accountKey);
        if (!Files.exists(f)) return null;
        try {
            String encrypted = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            String json = CryptoUtil.decrypt(encrypted);
            Map<String, Object> obj = MiniJson.parseObject(json);
            return MiniJson.getString(obj, "refresh_token", null);
        } catch (Exception e) {
            return null; // corrupted / undecryptable cache — treat as "not enrolled"
        }
    }

    private Path tokenFile(String accountKey) {
        return tokenDir.resolve(safeName(accountKey) + ".oauth");
    }

    private String safeName(String accountKey) {
        return accountKey.replaceAll("[^a-zA-Z0-9._@-]", "_");
    }

    private String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
