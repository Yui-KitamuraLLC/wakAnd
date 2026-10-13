package pro.eng.yui.android.wakAnd;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;

/** The old URL/key remain intact when the selected destination changes. */
final class ConnectionSettings {
    static final Object LOCK = new Object();
    private static final Set<HttpURLConnection> REQUESTS = new HashSet<>();
    static final String OFFICIAL_API = "https://api.wakatime.com/api/v1";
    static final String TARGET = "connection_target";
    private static final String GENERATION = "connection_generation";
    private final SharedPreferences prefs;

    ConnectionSettings(Context context) {
        this(context.getSharedPreferences("wakand_prefs", Context.MODE_PRIVATE));
    }
    ConnectionSettings(SharedPreferences preferences) {
        prefs = preferences;
        synchronized (LOCK) {
            if (!prefs.contains(TARGET)) {
                boolean official = defaultOfficial(prefs.getString("api_url", WakaTimeClient.getDefaultApiUrl()));
                prefs.edit().putString(TARGET, official ? "wakatime" : "custom")
                        .putString("official_legacy_api_key", official ? prefs.getString("api_key", "") : "").apply();
            }
        }
    }

    static boolean defaultOfficial(String url) {
        return url == null || url.isEmpty() || OAuthPolicy.officialApi(url);
    }

    static String validateCustom(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || "wakatime.com".equalsIgnoreCase(uri.getHost()) || "api.wakatime.com".equalsIgnoreCase(uri.getHost())
                    || uri.getRawPath().contains("..") || uri.getRawPath().contains("%")) return null;
            return url.replaceAll("/+$", "");
        } catch (Exception error) { return null; }
    }

    ApiRequestRunner.Target snapshot() {
        synchronized (LOCK) {
            String oldUrl = prefs.getString("api_url", WakaTimeClient.getDefaultApiUrl());
            boolean official = "wakatime".equals(prefs.getString(TARGET, defaultOfficial(oldUrl) ? "wakatime" : "custom"));
            return new ApiRequestRunner.Target(official, official ? OFFICIAL_API : oldUrl,
                    prefs.getString(official ? "official_legacy_api_key" : "api_key", ""), prefs.getLong(GENERATION, 0));
        }
    }

    boolean current(ApiRequestRunner.Target target) { return target.same(snapshot()); }

    static void register(HttpURLConnection connection, ApiRequestRunner.Check check) throws ApiRequestRunner.Failure {
        synchronized (LOCK) { check.run(); REQUESTS.add(connection); }
    }
    static void release(HttpURLConnection connection) {
        synchronized (LOCK) { REQUESTS.remove(connection); }
        connection.disconnect();
    }
    private static void stopRequests() {
        for (HttpURLConnection connection : REQUESTS) connection.disconnect();
        REQUESTS.clear();
    }

    void select(boolean official) {
        synchronized (LOCK) {
            if (snapshot().official == official) return;
            prefs.edit().putString(TARGET, official ? "wakatime" : "custom")
                    .putLong(GENERATION, prefs.getLong(GENERATION, 0) + 1).apply();
            stopRequests();
        }
    }

    void saveCustom(String url, String key) {
        synchronized (LOCK) {
            prefs.edit().putString("api_url", url).putString("api_key", key)
                    .putLong(GENERATION, prefs.getLong(GENERATION, 0) + 1).apply();
            stopRequests();
        }
    }
}
