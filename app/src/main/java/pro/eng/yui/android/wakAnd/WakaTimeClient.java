package pro.eng.yui.android.wakAnd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.os.Build;

public class WakaTimeClient {

    private static final String TAG = "WakaTimeClient";
    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_PROJECT_NAME = "project_name";
    private static final String DEFAULT_HOST = "https://wakatime.com";
    private static final String DEFAULT_API_PATH = "/api/v1";
    private static final String DEFAULT_API_URL = DEFAULT_HOST + DEFAULT_API_PATH;

    public static String getDefaultApiUrl() {
        return DEFAULT_API_URL;
    }
    private static final String DEFAULT_PROJECT_NAME = "Unknown Project";
    private static final long HEARTBEAT_INTERVAL_MS = 120_000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final String userAgent;
    private String lastEntity = "";
    private long lastHeartbeatTime = 0;

    public WakaTimeClient(Context context) {
        this.context = context.getApplicationContext();
        String version;
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "unknown";
        }
        String javaVersion = System.getProperty("java.specification.version", "unknown");
        this.userAgent = "wakatime/1.0.0 (Android-linux)" +
                " Java/" + javaVersion + " wakAnd/" + version + " wakAnd-wakatime/" + version;
    }

    public void sendHeartbeat(String entity, String projectOverride, boolean isUrl) {
        if (entity == null || entity.isEmpty()) {
            return;
        }

        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            Log.d(TAG, "API key not configured, skipping heartbeat");
            return;
        }

        long now = System.currentTimeMillis();
        boolean isSameEntity = entity.equals(lastEntity);
        boolean withinInterval = (now - lastHeartbeatTime) < HEARTBEAT_INTERVAL_MS;

        if (isSameEntity && withinInterval) {
            return;
        }

        lastEntity = entity;
        lastHeartbeatTime = now;

        double unixTime = now / 1000.0;

        executor.execute(() -> {
            try {
                JSONObject heartbeat = new JSONObject();
                heartbeat.put("entity", entity);
                heartbeat.put("type", isUrl ? "url" : "app");
                heartbeat.put("time", unixTime);
                heartbeat.put("project", projectOverride != null ? projectOverride : getProjectName());
                heartbeat.put("user_agent", userAgent);

                JSONArray heartbeats = new JSONArray();
                heartbeats.put(heartbeat);

                String urlStr = getApiUrl() + "/users/current/heartbeats.bulk";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                String encoded = Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("X-Machine-Name", Build.MODEL);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(30_000);

                byte[] body = heartbeats.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                    Log.d(TAG, "Heartbeat sent: " + entity);
                } else {
                    Log.w(TAG, "Heartbeat failed: HTTP " + responseCode);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send heartbeat", e);
            }
        });
    }

    private String getApiKey() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_API_KEY, "");
    }

    private String getApiUrl() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(KEY_API_URL, DEFAULT_API_URL);
        return (url.isEmpty() == false) ? url : DEFAULT_API_URL;
    }

    private String getProjectName() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_PROJECT_NAME, DEFAULT_PROJECT_NAME);
        return (name.isEmpty() == false) ? name : DEFAULT_PROJECT_NAME;
    }

    public interface ProjectsCallback {
        void onResult(List<String> projectNames);
    }

    public void fetchProjects(ProjectsCallback callback) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onResult(new ArrayList<>());
            return;
        }

        executor.execute(() -> {
            try {
                String urlStr = getApiUrl() + "/users/current/projects";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                String encoded = Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("X-Machine-Name", Build.MODEL);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray data = json.getJSONArray("data");
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < data.length(); i++) {
                        names.add(data.getJSONObject(i).getString("name"));
                    }
                    names.sort(String.CASE_INSENSITIVE_ORDER);
                    callback.onResult(names);
                } else {
                    Log.w(TAG, "fetchProjects failed: HTTP " + code);
                    callback.onResult(new ArrayList<>());
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch projects", e);
                callback.onResult(new ArrayList<>());
            }
        });
    }

    public interface UserCallback {
        void onResult(String displayName, String dashboardUrl, String profileUrl);
    }

    public void fetchCurrentUser(UserCallback callback) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onResult(null, null, null);
            return;
        }

        executor.execute(() -> {
            try {
                String urlStr = getApiUrl() + "/users/current";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                String encoded = Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("X-Machine-Name", Build.MODEL);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject data = json.getJSONObject("data");
                    String displayName = data.optString("display_name", data.optString("username", ""));
                    String host = getApiUrl().replace(DEFAULT_API_PATH, "");
                    String dashboardUrl = host + "/dashboard";
                    String userId = data.optString("id", "");
                    String profileUrl = userId.isEmpty() ? null : host + "/@" + userId;
                    callback.onResult(displayName, dashboardUrl, profileUrl);
                } else {
                    Log.w(TAG, "fetchCurrentUser failed: HTTP " + code);
                    callback.onResult(null, null, null);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch current user", e);
                callback.onResult(null, null, null);
            }
        });
    }

    public interface ExternalDurationCallback {
        void onResult(boolean success, String message);
    }

    /**
     * Manually logs an offline / non-digital activity to WakaTime via the external_durations API.
     * See https://wakatime.com/developers#external_durations
     *
     * @param project  existing project name to attribute the time to
     * @param category WakaTime category value (e.g. "communicating", "designing")
     * @param meta     free-form note synced to WakaTime (may be empty)
     * @param startSec start time as a UNIX epoch in seconds
     * @param endSec   end time as a UNIX epoch in seconds
     */
    public void sendExternalDuration(String project, String category, String meta,
                                     double startSec, double endSec, ExternalDurationCallback callback) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onResult(false, "no_api_key");
            return;
        }

        executor.execute(() -> {
            try {
                String entity = (meta != null && !meta.isEmpty()) ? meta : category;

                JSONObject body = new JSONObject();
                body.put("external_id", UUID.randomUUID().toString());
                body.put("entity", entity);
                body.put("type", "app");
                body.put("category", category);
                body.put("start_time", startSec);
                body.put("end_time", endSec);
                body.put("project", project);
                if (meta != null && !meta.isEmpty()) {
                    body.put("meta", meta);
                }
                body.put("user_agent", userAgent);

                String urlStr = getApiUrl() + "/users/current/external_durations";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                String encoded = Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("X-Machine-Name", Build.MODEL);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(30_000);

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                boolean ok = code == HttpURLConnection.HTTP_OK
                        || code == HttpURLConnection.HTTP_CREATED
                        || code == HttpURLConnection.HTTP_ACCEPTED;
                if (ok) {
                    Log.d(TAG, "External duration sent: " + entity);
                    callback.onResult(true, null);
                } else {
                    Log.w(TAG, "External duration failed: HTTP " + code);
                    callback.onResult(false, "HTTP " + code);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send external duration", e);
                callback.onResult(false, e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
