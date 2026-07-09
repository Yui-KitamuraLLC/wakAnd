package eng.pro.yui.android.wakand;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WakaTimeClient {

    private static final String TAG = "WakaTimeClient";
    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_PROJECT_NAME = "project_name";
    private static final String DEFAULT_API_URL = "https://wakatime.com/api/v1";
    private static final String DEFAULT_PROJECT_NAME = "Unknown Project";
    private static final long HEARTBEAT_INTERVAL_MS = 120_000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String lastEntity = "";
    private long lastHeartbeatTime = 0;

    public WakaTimeClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void sendHeartbeat(String entity, String appCategory) {
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
                heartbeat.put("type", "app");
                heartbeat.put("category", appCategory != null ? appCategory : "browsing");
                heartbeat.put("time", unixTime);
                heartbeat.put("project", getProjectName());
                heartbeat.put("language", "Android");

                JSONArray heartbeats = new JSONArray();
                heartbeats.put(heartbeat);

                String urlStr = getApiUrl() + "/users/current/heartbeats.bulk";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                String encoded = Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
                conn.setRequestProperty("User-Agent", "wakAnd/1.0 android-wakatime");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(30_000);

                byte[] body = heartbeats.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 201 || responseCode == 202) {
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
        return (url == null || url.isEmpty()) ? DEFAULT_API_URL : url;
    }

    private String getProjectName() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_PROJECT_NAME, DEFAULT_PROJECT_NAME);
        return (name == null || name.isEmpty()) ? DEFAULT_PROJECT_NAME : name;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
