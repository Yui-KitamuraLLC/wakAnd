package eng.pro.yui.android.wakand;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_PROJECT_NAME = "project_name";
    private static final String KEY_PROJECT_ENABLED = "project_enabled";
    private static final String KEY_LANG = "lang";
    private static final long POLL_INTERVAL_MS = 3000;

    private TextView txtUserName;
    private TextView txtDashboardLink;
    private TextInputEditText editApiKey;
    private TextView txtApiKeyWarning;
    private String savedApiKey = "";
    private TextInputEditText editApiUrl;
    private TextInputEditText editProjectName;
    private TextInputLayout layoutProjectName;
    private SwitchMaterial switchProject;
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

        txtUserName = findViewById(R.id.txtUserName);
        txtDashboardLink = findViewById(R.id.txtDashboardLink);
        editApiKey = findViewById(R.id.editApiKey);
        txtApiKeyWarning = findViewById(R.id.txtApiKeyWarning);
        ImageButton btnClearApiKey = findViewById(R.id.btnClearApiKey);
        editApiUrl = findViewById(R.id.editApiUrl);
        editProjectName = findViewById(R.id.editProjectName);
        layoutProjectName = findViewById(R.id.layoutProjectName);
        switchProject = findViewById(R.id.switchProject);
        txtCurrentApp = findViewById(R.id.txtCurrentApp);
        txtCurrentUrl = findViewById(R.id.txtCurrentUrl);

        Button btnSave = findViewById(R.id.btnSave);
        Button btnGrantUsageAccess = findViewById(R.id.btnGrantUsageAccess);
        Button btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        Button btnLang = findViewById(R.id.btnLang);
        Button btnAppList = findViewById(R.id.btnAppList);

        loadSettings();

        switchProject.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutProjectName.setVisibility(isChecked ? android.view.View.VISIBLE : android.view.View.GONE);
        });

        editApiKey.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                boolean changed = !s.toString().equals(savedApiKey);
                txtApiKeyWarning.setVisibility(changed ? android.view.View.VISIBLE : android.view.View.GONE);
            }
        });

        btnClearApiKey.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(R.string.confirm_clear_api_key)
                    .setPositiveButton(R.string.yes, (dialog, which) -> {
                        editApiKey.setText("");
                        savedApiKey = "";
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putString(KEY_API_KEY, "").apply();
                        txtApiKeyWarning.setVisibility(android.view.View.GONE);
                        txtUserName.setText(R.string.user_not_linked);
                        txtDashboardLink.setVisibility(android.view.View.GONE);
                        Toast.makeText(this, R.string.api_key_cleared, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.no, null)
                    .show();
        });

        requestNotificationPermissionAndStart();

        btnSave.setOnClickListener(v -> saveSettings());
        btnGrantUsageAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        btnEnableAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btnAppList.setOnClickListener(v ->
                startActivity(new Intent(this, AppListActivity.class)));
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
        savedApiKey = prefs.getString(KEY_API_KEY, "");
        editApiKey.setText(savedApiKey);
        txtApiKeyWarning.setVisibility(android.view.View.GONE);
        editApiUrl.setText(prefs.getString(KEY_API_URL, "https://wakatime.com/api/v1"));
        boolean projectEnabled = prefs.getBoolean(KEY_PROJECT_ENABLED, false);
        switchProject.setChecked(projectEnabled);
        layoutProjectName.setVisibility(projectEnabled ? android.view.View.VISIBLE : android.view.View.GONE);
        editProjectName.setText(prefs.getString(KEY_PROJECT_NAME, ""));
        fetchAndDisplayUser();
    }

    private void fetchAndDisplayUser() {
        WakaTimeClient client = new WakaTimeClient(this);
        client.fetchCurrentUser((displayName, dashboardUrl) -> runOnUiThread(() -> {
            if (displayName != null && !displayName.isEmpty()) {
                txtUserName.setText(displayName);
                txtDashboardLink.setVisibility(android.view.View.VISIBLE);
                txtDashboardLink.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl));
                    startActivity(intent);
                });
            } else {
                txtUserName.setText(R.string.user_not_linked);
                txtDashboardLink.setVisibility(android.view.View.GONE);
            }
            client.shutdown();
        }));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_API_KEY, String.valueOf(editApiKey.getText()))
                .putString(KEY_API_URL, String.valueOf(editApiUrl.getText()))
                .putBoolean(KEY_PROJECT_ENABLED, switchProject.isChecked())
                .putString(KEY_PROJECT_NAME, switchProject.isChecked() ? String.valueOf(editProjectName.getText()) : "")
                .apply();
        savedApiKey = String.valueOf(editApiKey.getText());
        txtApiKeyWarning.setVisibility(android.view.View.GONE);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        fetchAndDisplayUser();
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

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                startService(new Intent(this, TrackingService.class));
            });

    private void requestNotificationPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startService(new Intent(this, TrackingService.class));
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
