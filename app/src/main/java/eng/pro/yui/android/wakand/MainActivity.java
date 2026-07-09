package eng.pro.yui.android.wakand;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_PROJECT_NAME = "project_name";
    private static final String KEY_LANG = "lang";
    private static final long POLL_INTERVAL_MS = 3000;

    private TextInputEditText editUserName;
    private TextInputEditText editApiKey;
    private TextInputEditText editApiUrl;
    private TextInputEditText editProjectName;
    private TextView txtCurrentApp;
    private TextView txtCurrentUrl;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            updateCurrentApp();
            updateCurrentUrl();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = newBase.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lang = prefs.getString(KEY_LANG, "ja");
        Locale locale = Locale.forLanguageTag(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.setLocale(locale);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editUserName = findViewById(R.id.editUserName);
        editApiKey = findViewById(R.id.editApiKey);
        editApiUrl = findViewById(R.id.editApiUrl);
        editProjectName = findViewById(R.id.editProjectName);
        txtCurrentApp = findViewById(R.id.txtCurrentApp);
        txtCurrentUrl = findViewById(R.id.txtCurrentUrl);

        Button btnSave = findViewById(R.id.btnSave);
        Button btnGrantUsageAccess = findViewById(R.id.btnGrantUsageAccess);
        Button btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        Button btnLang = findViewById(R.id.btnLang);

        loadSettings();

        startService(new Intent(this, TrackingService.class));

        btnSave.setOnClickListener(v -> saveSettings());
        btnGrantUsageAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        btnEnableAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btnLang.setOnClickListener(v -> toggleLanguage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollRunnable);
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        editUserName.setText(prefs.getString(KEY_USER_NAME, ""));
        editApiKey.setText(prefs.getString(KEY_API_KEY, ""));
        editApiUrl.setText(prefs.getString(KEY_API_URL, "https://wakatime.com/api/v1"));
        editProjectName.setText(prefs.getString(KEY_PROJECT_NAME, "wakAnd"));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_USER_NAME, String.valueOf(editUserName.getText()))
                .putString(KEY_API_KEY, String.valueOf(editApiKey.getText()))
                .putString(KEY_API_URL, String.valueOf(editApiUrl.getText()))
                .putString(KEY_PROJECT_NAME, String.valueOf(editProjectName.getText()))
                .apply();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void toggleLanguage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString(KEY_LANG, "ja");
        String next = current.equals("ja") ? "en" : "ja";
        prefs.edit().putString(KEY_LANG, next).apply();
        recreate();
    }

    private void updateCurrentApp() {
        if (!hasUsageStatsPermission()) {
            txtCurrentApp.setText("Current App: (usage access not granted)");
            return;
        }

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);

        if (stats == null || stats.isEmpty()) {
            txtCurrentApp.setText("Current App: —");
            return;
        }

        SortedMap<Long, UsageStats> sorted = new TreeMap<>();
        for (UsageStats s : stats) {
            sorted.put(s.getLastTimeUsed(), s);
        }
        String topPackage = sorted.get(sorted.lastKey()).getPackageName();
        txtCurrentApp.setText("Current App: " + topPackage);
    }

    private void updateCurrentUrl() {
        String url = ChromeUrlAccessibilityService.getLastUrl();
        if (url != null && !url.isEmpty()) {
            txtCurrentUrl.setText("Current URL: " + url);
        } else {
            txtCurrentUrl.setText("Current URL: —");
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
