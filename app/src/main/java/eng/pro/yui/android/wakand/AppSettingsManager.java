package eng.pro.yui.android.wakand;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class AppSettingsManager {

    private static final String PREFS_NAME = "wakand_app_settings";
    private static final String KEY_APP_SETTINGS = "app_settings_json";

    private final SharedPreferences prefs;

    public AppSettingsManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static class AppSetting {
        public boolean enabled;
        public String projectName;

        public AppSetting(boolean enabled, String projectName) {
            this.enabled = enabled;
            this.projectName = projectName;
        }
    }

    public Map<String, AppSetting> loadAll() {
        Map<String, AppSetting> result = new HashMap<>();
        String json = prefs.getString(KEY_APP_SETTINGS, "{}");
        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String appName = keys.next();
                JSONObject obj = root.getJSONObject(appName);
                result.put(appName, new AppSetting(
                        obj.optBoolean("enabled", false),
                        obj.optString("projectName", "AndroidUsage")
                ));
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    public void saveAll(Map<String, AppSetting> settings) {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, AppSetting> entry : settings.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("enabled", entry.getValue().enabled);
                obj.put("projectName", entry.getValue().projectName);
                root.put(entry.getKey(), obj);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_APP_SETTINGS, root.toString()).apply();
    }

    public AppSetting getSettingFor(String appName) {
        Map<String, AppSetting> all = loadAll();
        return all.get(appName);
    }

    public boolean isAppEnabled(String appName) {
        AppSetting setting = getSettingFor(appName);
        return setting != null && setting.enabled;
    }

    public boolean hasAnySettings() {
        String json = prefs.getString(KEY_APP_SETTINGS, "{}");
        try {
            JSONObject root = new JSONObject(json);
            return root.length() > 0;
        } catch (JSONException e) {
            return false;
        }
    }
}
