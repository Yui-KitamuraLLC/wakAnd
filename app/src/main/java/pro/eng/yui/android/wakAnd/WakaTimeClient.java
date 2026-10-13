package pro.eng.yui.android.wakAnd;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WakaTimeClient {
    public static String getDefaultApiUrl() { return "https://wakatime.com/api/v1"; }
    private final Context context;
    private final ConnectionSettings settings;
    private final OAuthManager oauth;
    private final ApiRequestRunner runner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String userAgent;
    private String lastEntity = "";
    private long lastHeartbeatTime, lastDestination = -1;
    private volatile String heartbeatError;
    private volatile long heartbeatDestination = -1;

    public static final class Result<T> {
        public final T value;
        public final String error;
        public final int httpStatus;
        private Result(T value, String error, int status) { this.value = value; this.error = error; this.httpStatus = status; }
        public boolean successful() { return error == null; }
        static <T> Result<T> ok(T value, int status) { return new Result<>(value, null, status); }
        static <T> Result<T> failed(String error, int status) { return new Result<>(null, error, status); }
    }
    public static final class User {
        public final String displayName, dashboardUrl, profileUrl;
        User(String name, String dashboard, String profile) { displayName = name; dashboardUrl = dashboard; profileUrl = profile; }
    }
    public interface ProjectsCallback { void onResult(Result<List<String>> result); }
    public interface UserCallback { void onResult(Result<User> result); }
    public interface ExternalDurationCallback { void onResult(Result<Void> result); }

    public WakaTimeClient(Context context) {
        this.context = context.getApplicationContext();
        settings = new ConnectionSettings(this.context); oauth = new OAuthManager(this.context);
        String version;
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception error) { version = "unknown"; }
        userAgent = "wakatime/1.0.0 (Android-linux) Java/" + System.getProperty("java.specification.version", "unknown")
                + " wakAnd/" + version + " wakAnd-wakatime/" + version;
        runner = new ApiRequestRunner(settings::current, new ApiRequestRunner.Authorization() {
            public ApiRequestRunner.Credential prepare(String url, String link, boolean unauthorized, long generation) throws ApiRequestRunner.Failure {
                try { return oauth.credential(url, link, unauthorized, generation); }
                catch (OAuthManager.Failure error) { throw new ApiRequestRunner.Failure(error.code); }
            }
            public boolean current(ApiRequestRunner.Credential credential) { return oauth.current(credential); }
            public boolean legacyAllowed() { return oauth.legacyHeartbeatAllowed(); }
            public void rejected(ApiRequestRunner.Credential credential) throws ApiRequestRunner.Failure {
                try { oauth.reject(credential); }
                catch (OAuthManager.Failure error) { throw new ApiRequestRunner.Failure(error.code); }
            }
        }, this::send);
    }

    private ApiRequestRunner.Response send(String url, String method, byte[] payload, String authorization,
                                           ApiRequestRunner.Check check) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            ConnectionSettings.register(connection, check);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("X-Machine-Name", Build.MODEL);
            connection.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                check.run();
                try (java.io.OutputStream stream = connection.getOutputStream()) {
                    check.run(); stream.write(payload);
                }
            } else { check.run(); }
            int status = connection.getResponseCode();
            // Provider error bodies, URLs and exceptions never reach UI, logs or issue reports.
            if (status < 200 || status >= 300) return new ApiRequestRunner.Response(status, "");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new ApiRequestRunner.Failure("invalid_response");
                    output.write(buffer, 0, count);
                }
                String body = output.toString("UTF-8");
                if (url.equals(ConnectionSettings.OFFICIAL_API + "/users/current/heartbeats.bulk"))
                    status = ApiResponsePolicy.heartbeatStatus(body);
                return new ApiRequestRunner.Response(status, body);
            }
        } finally { ConnectionSettings.release(connection); }
    }

    private static String httpError(int status) {
        if (status == 401) return "unauthorized";
        if (status == 403) return "forbidden";
        if (status == 429) return "rate_limited";
        if (status >= 300 && status < 400) return "redirect_rejected";
        return status >= 500 ? "provider_error" : "invalid_request";
    }
    private Result<ApiRequestRunner.Response> request(ApiRequestRunner.Target target, String link, boolean legacy,
                                                     String path, String body) {
        try {
            ApiRequestRunner.Response response = runner.execute(target, link, legacy, path,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8));
            return response.status >= 200 && response.status < 300 ? Result.ok(response, response.status)
                    : Result.failed(httpError(response.status), response.status);
        } catch (ApiRequestRunner.Failure error) { return Result.failed(error.code, 0); }
    }
    private boolean stillCurrent(ApiRequestRunner.Target target, String link) {
        return settings.current(target) && (!target.official || link.equals(oauth.activeIdentity()));
    }

    public synchronized void sendHeartbeat(String entity, String projectOverride, boolean isUrl) {
        if (entity == null || entity.isEmpty()) return;
        ApiRequestRunner.Target target = settings.snapshot();
        String link = target.official ? oauth.activeIdentity() : "";
        boolean legacy = target.official && link.isEmpty() && oauth.legacyHeartbeatAllowed();
        long now = System.currentTimeMillis();
        if (target.generation == lastDestination && entity.equals(lastEntity) && now - lastHeartbeatTime < 120000) return;
        lastEntity = entity; lastHeartbeatTime = now; lastDestination = target.generation;
        String project = projectOverride != null ? projectOverride : context.getSharedPreferences("wakand_prefs", Context.MODE_PRIVATE)
                .getString("project_name", "Unknown Project");
        executor.execute(() -> {
            try {
                JSONObject heartbeat = new JSONObject().put("entity", entity).put("type", isUrl ? "url" : "app")
                        .put("time", now / 1000.0).put("project", project).put("user_agent", userAgent);
                Result<ApiRequestRunner.Response> result = request(target, link, legacy, "/users/current/heartbeats.bulk",
                        new JSONArray().put(heartbeat).toString());
                String error = result.error;
                if (stillCurrent(target, link)) { heartbeatError = error; heartbeatDestination = target.generation; }
            } catch (Exception error) { if (stillCurrent(target, link)) { heartbeatError = "invalid_response"; heartbeatDestination = target.generation; } }
        });
    }
    public String heartbeatError() { return heartbeatDestination == settings.snapshot().generation ? heartbeatError : null; }

    public void fetchProjects(ProjectsCallback callback) {
        ApiRequestRunner.Target target = settings.snapshot(); String link = target.official ? oauth.activeIdentity() : "";
        executor.execute(() -> {
            Result<ApiRequestRunner.Response> response = request(target, link, false, "/users/current/projects", null);
            if (!response.successful()) { callback.onResult(Result.failed(response.error, response.httpStatus)); return; }
            try {
                JSONArray data = new JSONObject(response.value.body).getJSONArray("data"); List<String> names = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) names.add(data.getJSONObject(i).getString("name"));
                names.sort(String.CASE_INSENSITIVE_ORDER);
                callback.onResult(stillCurrent(target, link) ? Result.ok(names, response.httpStatus) : Result.failed("destination_changed", 0));
            } catch (Exception error) { callback.onResult(Result.failed("invalid_response", response.httpStatus)); }
        });
    }
    public void fetchCurrentUser(UserCallback callback) {
        ApiRequestRunner.Target target = settings.snapshot(); String link = target.official ? oauth.activeIdentity() : "";
        executor.execute(() -> {
            Result<ApiRequestRunner.Response> response = request(target, link, false, "/users/current", null);
            if (!response.successful()) { callback.onResult(Result.failed(response.error, response.httpStatus)); return; }
            try {
                JSONObject data = new JSONObject(response.value.body).getJSONObject("data");
                String name = data.optString("display_name", data.optString("username", ""));
                if (name.isEmpty()) name = data.optString("username", "");
                java.net.URI uri = new java.net.URI(target.apiUrl);
                String host = target.official ? "https://wakatime.com" : uri.getScheme() + "://" + uri.getRawAuthority();
                String id = data.optString("id", "");
                User user = new User(name, host + "/dashboard", id.matches("[A-Za-z0-9_-]+") ? host + "/@" + id : null);
                callback.onResult(stillCurrent(target, link) ? Result.ok(user, response.httpStatus) : Result.failed("destination_changed", 0));
            } catch (Exception error) { callback.onResult(Result.failed("invalid_response", response.httpStatus)); }
        });
    }

    public void sendExternalDuration(String externalId, String project, String category, String meta,
                                     double startSec, double endSec, ExternalDurationCallback callback) {
        ApiRequestRunner.Target target = settings.snapshot(); String link = target.official ? oauth.activeIdentity() : "";
        executor.execute(() -> {
            try {
                UUID.fromString(externalId);
                JSONObject body = new JSONObject().put("external_id", externalId).put("entity", meta.isEmpty() ? category : meta)
                        .put("type", "app").put("category", category).put("start_time", startSec).put("end_time", endSec)
                        .put("project", project).put("user_agent", userAgent);
                if (!meta.isEmpty()) body.put("meta", meta);
                Result<ApiRequestRunner.Response> response = request(target, link, false, "/users/current/external_durations", body.toString());
                callback.onResult(response.successful() ? Result.ok(null, response.httpStatus) : Result.failed(response.error, response.httpStatus));
            } catch (Exception error) { callback.onResult(Result.failed("invalid_request", 0)); }
        });
    }
    public void shutdown() { executor.shutdownNow(); }
}
