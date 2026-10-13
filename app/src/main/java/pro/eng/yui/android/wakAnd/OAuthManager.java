package pro.eng.yui.android.wakAnd;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Call network methods off the UI thread. No provider refresh token is stored on Android. */
public final class OAuthManager {
    interface StateStore { JSONObject read() throws Exception; void write(JSONObject value) throws Exception; }
    interface RequestTransport { JSONObject post(String path, JSONObject body, JSONObject credentials) throws Failure; }
    private static final Object NETWORK_LOCK = new Object();
    public static final class Failure extends Exception {
        public final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    private final StateStore store;
    private final RequestTransport transport;
    public OAuthManager(Context context) { this(new OAuthStore(context.getApplicationContext()), null); }
    OAuthManager(StateStore store, RequestTransport transport) { this.store = store; this.transport = transport; }
    private JSONObject read() throws Failure {
        try { return store.read(); } catch (Exception error) { throw new Failure("reauth_required"); }
    }
    private void write(JSONObject value) throws Failure {
        try { store.write(value); } catch (Exception error) { throw new Failure("storage_unavailable"); }
    }
    private void commitResponse(JSONObject value, String before) throws Failure {
        synchronized (OAuthStore.LOCK) {
            if (!read().toString().equals(before)) throw new Failure("link_disconnected");
            write(value);
        }
    }
    private static JSONObject response(HttpURLConnection connection, int status) throws Exception {
        try (InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            if (stream == null) throw new Failure("service_unavailable");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[2048]; int count;
            while ((count = stream.read(bytes)) != -1) {
                if (output.size() + count > 32768) throw new Failure("service_unavailable");
                output.write(bytes, 0, count);
            }
            return new JSONObject(output.toString("UTF-8"));
        }
    }
    private JSONObject post(String path, JSONObject body, JSONObject credentials) throws Failure {
        if (transport != null) return transport.post(path, body, credentials);
        HttpURLConnection connection = null;
        try {
            // path is a private, fixed call-site constant. Never follow redirects with credentials.
            connection = (HttpURLConnection) new URL(OAuthPolicy.AUTH_BASE + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000); connection.setReadTimeout(30000); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            if (credentials != null) {
                OAuthPolicy.decode(credentials.getString("link_id"), 16); OAuthPolicy.decode(credentials.getString("secret"), 32);
                connection.setRequestProperty("Authorization", "Bearer " + credentials.getString("secret"));
                connection.setRequestProperty("X-Wakand-Link-Id", credentials.getString("link_id"));
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode(); JSONObject result = response(connection, status);
            if (status >= 200 && status < 300) return result;
            String code = result.optString("error", "service_unavailable");
            switch (code) {
                case "device_unauthorized": case "reauth_required": case "link_expired": case "link_disconnected":
                case "operation_in_progress": case "rate_limited": case "provider_error": case "invalid_request":
                    throw new Failure(code);
                default: throw new Failure("service_unavailable");
            }
        } catch (Failure error) { throw error; }
        catch (Exception error) { throw new Failure("network_unavailable"); }
        finally { if (connection != null) connection.disconnect(); }
    }
    public String status() {
        synchronized (OAuthStore.LOCK) {
            try {
                JSONObject doc = read();
                if (doc.has("pending")) return "pending";
                if (doc.has("active")) {
                    String state = doc.getJSONObject("active").optString("status", "active");
                    return "active".equals(state) && doc.optBoolean("provider_confirmation_required") ? "active_confirmation_required" : state;
                }
                if (doc.optBoolean("reauth_required")) return "reauth_required";
                if (doc.optBoolean("provider_confirmation_required")) return "provider_confirmation_required";
                return doc.optJSONArray("revocations") != null && doc.optJSONArray("revocations").length() > 0 ? "disconnect_pending" : "unlinked";
            } catch (Exception error) { return "reauth_required"; }
        }
    }
    public String begin() throws Failure {
        synchronized (NETWORK_LOCK) {
            try {
                JSONObject doc;
                JSONObject pending;
                synchronized (OAuthStore.LOCK) {
                    try { doc = read(); } catch (Failure error) { doc = new JSONObject().put("provider_confirmation_required", true); }
                    // A damaged/absent Keystore requires reauthorization, never clearing app settings.
                    JSONObject oldPending = doc.optJSONObject("pending");
                    if (oldPending != null && oldPending.has("link_id")) queue(doc, oldPending);
                    pending = new JSONObject().put("secret", OAuthPolicy.randomSecret()).put("verifier", OAuthPolicy.randomSecret());
                    doc.put("pending", pending); write(doc); // Persist before the start request.
                }
                String before = doc.toString();
                JSONObject result = post("v1/link/start", new JSONObject().put("device_secret_hash", OAuthPolicy.hash(pending.getString("secret"), true))
                        .put("verifier_challenge", OAuthPolicy.hash(pending.getString("verifier"), false)), null);
                String id = result.getString("link_id"), url = result.getString("browser_url");
                if (!OAuthPolicy.launch(url, id)) throw new Failure("invalid_response");
                pending.put("link_id", id).put("expires_at", result.getLong("expires_at")); commitResponse(doc, before); return url;
            } catch (Failure error) { throw error; } catch (Exception error) { throw new Failure("storage_unavailable"); }
        }
    }
    public boolean acceptsCompletion(String url) {
        synchronized (OAuthStore.LOCK) {
            try { JSONObject pending = read().optJSONObject("pending"); return pending != null && OAuthPolicy.completion(url, pending.optString("link_id")); }
            catch (Exception error) { return false; }
        }
    }
    private static JSONObject credentials(JSONObject row) throws Exception {
        return new JSONObject().put("link_id", row.getString("link_id")).put("secret", row.getString("secret"));
    }
    private static void queue(JSONObject doc, JSONObject row) throws Exception {
        JSONArray pending = doc.optJSONArray("revocations"); if (pending == null) pending = new JSONArray();
        String id = row.getString("link_id");
        for (int i = 0; i < pending.length(); i++) if (id.equals(pending.getJSONObject(i).optString("link_id"))) return;
        pending.put(credentials(row)); doc.put("revocations", pending);
    }
    private static void acceptToken(JSONObject target, JSONObject result) throws Exception {
        String token = result.getString("access_token");
        if (!"Bearer".equals(result.getString("token_type")) || !token.matches("[\\x21-\\x7e]{1,8192}")
                || result.getLong("generation") < 1 || result.getLong("expires_at") <= 0 || result.has("refresh_token")) throw new Failure("invalid_response");
        target.put("access_token", token).put("expires_at", result.getLong("expires_at"))
                .put("generation", result.getLong("generation")).put("granted_scope", result.getString("granted_scope")).put("status", "active");
    }
    public void complete() throws Failure {
        synchronized (NETWORK_LOCK) {
            try {
                JSONObject doc;
                synchronized (OAuthStore.LOCK) { doc = read(); }
                JSONObject pending = doc.optJSONObject("pending");
                if (pending == null || !pending.has("link_id")) return;
                String before = doc.toString();
                JSONObject result;
                try { result = post("v1/link/complete", new JSONObject().put("verifier", pending.getString("verifier")), pending); }
                catch (Failure error) {
                    if (!"link_expired".equals(error.code)) throw error;
                    // A lost response may already have activated the link. The expired session
                    // cannot be reused; device credentials can recover the active generation.
                    result = post("v1/token/refresh", new JSONObject().put("observed_generation", 0).put("reason", "expired"), pending);
                }
                if (result.optBoolean("pending")) throw new Failure("authorization_pending");
                JSONObject active = credentials(pending); acceptToken(active, result);
                JSONObject previous = doc.optJSONObject("active"); if (previous != null) queue(doc, previous);
                doc.put("active", active); doc.put("oauth_used", true); doc.remove("pending"); doc.remove("reauth_required"); commitResponse(doc, before);
            } catch (Failure error) {
                if ("link_expired".equals(error.code) || "reauth_required".equals(error.code) || "link_disconnected".equals(error.code)) {
                    try { synchronized (OAuthStore.LOCK) { JSONObject doc = read(), pending = doc.optJSONObject("pending"); if (pending != null && pending.has("link_id")) { queue(doc, pending); doc.put("reauth_required", true); } doc.remove("pending"); write(doc); } }
                    catch (Exception ignored) { /* Keep persisted pending on storage failure. */ }
                }
                throw error;
            } catch (Exception error) { throw new Failure("storage_unavailable"); }
        }
        drainRevocations();
    }
    /** Adapter for #4. The caller must also check the selected target immediately before sending. */
    public String accessToken(String apiUrl, boolean unauthorized, long observedGeneration) throws Failure {
        if (!OAuthPolicy.officialApi(apiUrl)) throw new Failure("invalid_destination");
        synchronized (NETWORK_LOCK) {
            try {
                JSONObject doc;
                synchronized (OAuthStore.LOCK) { doc = read(); }
                JSONObject active = doc.optJSONObject("active");
                if (active == null || !"active".equals(active.optString("status"))) throw new Failure("reauth_required");
                long generation = active.getLong("generation");
                boolean sameUnauthorized = unauthorized && generation == observedGeneration;
                if (sameUnauthorized || active.getLong("expires_at") <= System.currentTimeMillis() / 1000 + 60) {
                    String before = doc.toString();
                    try {
                        JSONObject result = post("v1/token/refresh", new JSONObject().put("observed_generation", generation)
                                .put("reason", sameUnauthorized ? "unauthorized" : "expired"), active);
                        if (result.getLong("generation") < generation) throw new Failure("invalid_response");
                        acceptToken(active, result); commitResponse(doc, before);
                    } catch (Failure error) {
                        if ("reauth_required".equals(error.code) || "link_disconnected".equals(error.code) || "device_unauthorized".equals(error.code)) {
                            active.remove("access_token"); active.put("status", "reauth_required"); commitResponse(doc, before);
                        }
                        throw error;
                    }
                }
                synchronized (OAuthStore.LOCK) {
                    JSONObject current = read().optJSONObject("active");
                    if (current == null || !current.optString("link_id").equals(active.getString("link_id"))) throw new Failure("link_disconnected");
                    return current.getString("access_token");
                }
            } catch (Failure error) { throw error; } catch (Exception error) { throw new Failure("storage_unavailable"); }
        }
    }
    public long generation() throws Failure {
        synchronized (OAuthStore.LOCK) {
            try { return read().getJSONObject("active").getLong("generation"); }
            catch (Exception error) { throw new Failure("reauth_required"); }
        }
    }
    String activeIdentity() {
        synchronized (OAuthStore.LOCK) {
            try {
                JSONObject active = read().optJSONObject("active");
                return active == null ? "" : active.optString("link_id", "");
            } catch (Failure error) { return ""; }
        }
    }
    JSONObject authorizationInfo() {
        synchronized (OAuthStore.LOCK) {
            try {
                JSONObject active = read().optJSONObject("active");
                if (active == null) return new JSONObject();
                return new JSONObject().put("generation", active.getLong("generation"))
                        .put("expires_at", active.getLong("expires_at"))
                        .put("granted_scope", active.getString("granted_scope"));
            } catch (Exception error) { return new JSONObject(); }
        }
    }
    boolean legacyHeartbeatAllowed() {
        synchronized (OAuthStore.LOCK) {
            try {
                JSONObject doc = read();
                return !doc.optBoolean("oauth_used") && !doc.has("active") && !doc.optBoolean("reauth_required")
                        && !doc.has("revocations") && !doc.optBoolean("provider_confirmation_required");
            } catch (Failure error) { return false; }
        }
    }
    ApiRequestRunner.Credential credential(String url, String expectedLink, boolean unauthorized, long observed) throws Failure {
        synchronized (NETWORK_LOCK) {
            long before;
            synchronized (OAuthStore.LOCK) {
                JSONObject active = read().optJSONObject("active");
                if (active == null || !expectedLink.equals(active.optString("link_id"))) throw new Failure("link_disconnected");
                before = active.optLong("generation");
            }
            accessToken(url, unauthorized, observed);
            synchronized (OAuthStore.LOCK) {
                try {
                    JSONObject active = read().getJSONObject("active");
                    if (!expectedLink.equals(active.optString("link_id")) || !"active".equals(active.optString("status")))
                        throw new Failure("link_disconnected");
                    return new ApiRequestRunner.Credential(active.getString("access_token"), expectedLink,
                            active.getLong("generation"), active.getLong("generation") != before);
                } catch (Failure error) { throw error; } catch (Exception error) { throw new Failure("reauth_required"); }
            }
        }
    }
    boolean current(ApiRequestRunner.Credential credential) {
        synchronized (OAuthStore.LOCK) {
            try {
                JSONObject active = read().optJSONObject("active");
                return active != null && "active".equals(active.optString("status"))
                        && credential.linkId.equals(active.optString("link_id"))
                        && credential.generation == active.optLong("generation")
                        && credential.token.equals(active.optString("access_token"));
            } catch (Failure error) { return false; }
        }
    }
    void reject(ApiRequestRunner.Credential credential) throws Failure {
        synchronized (OAuthStore.LOCK) {
            if (!current(credential)) return;
            try {
                JSONObject doc = read(), active = doc.getJSONObject("active");
                active.remove("access_token"); active.put("status", "reauth_required"); write(doc);
            } catch (Failure error) { throw error; } catch (Exception error) { throw new Failure("storage_unavailable"); }
        }
    }
    public void disconnect() throws Failure {
        stopLocal();
        drainRevocations();
    }
    public void stopLocal() throws Failure {
        synchronized (OAuthStore.LOCK) {
            try {
                JSONObject doc = read(), active = doc.optJSONObject("active"), pending = doc.optJSONObject("pending");
                if (active != null) queue(doc, active);
                if (pending != null && pending.has("link_id")) queue(doc, pending);
                doc.remove("active"); doc.remove("pending"); doc.remove("reauth_required"); write(doc); // Local use stops before any network.
            } catch (Failure error) { throw error; } catch (Exception error) { throw new Failure("storage_unavailable"); }
        }
    }
    /** At most one queued request per call; credentials never return to active use. */
    public void drainRevocations() throws Failure {
        synchronized (NETWORK_LOCK) {
            try {
                JSONObject doc;
                synchronized (OAuthStore.LOCK) { doc = read(); }
                JSONArray queue = doc.optJSONArray("revocations");
                if (queue == null || queue.length() == 0) return;
                JSONObject row = queue.getJSONObject(0);
                JSONObject result = post("v1/link/disconnect", new JSONObject(), row);
                synchronized (OAuthStore.LOCK) {
                    doc = read(); queue = doc.optJSONArray("revocations");
                    if (result.optBoolean("provider_confirmation_required")) doc.put("provider_confirmation_required", true);
                    if (!result.optBoolean("revocation_pending") && queue != null) {
                        for (int i = 0; i < queue.length(); i++) {
                            if (row.getString("link_id").equals(queue.getJSONObject(i).optString("link_id"))) { queue.remove(i); break; }
                        }
                    }
                    write(doc);
                }
            } catch (Failure error) {
                if ("device_unauthorized".equals(error.code)) {
                    try { synchronized (OAuthStore.LOCK) { JSONObject doc = read(); doc.put("provider_confirmation_required", true); doc.getJSONArray("revocations").remove(0); write(doc); } }
                    catch (Exception ignored) { /* Retry safely next time. */ }
                }
                throw error;
            } catch (Exception error) { throw new Failure("storage_unavailable"); }
        }
    }
}
