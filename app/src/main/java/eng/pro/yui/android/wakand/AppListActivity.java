package eng.pro.yui.android.wakand;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppListActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_LANG = "lang";

    private AppSettingsManager appSettingsManager;
    private AppListAdapter adapter;

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
        setContentView(R.layout.activity_app_list);

        appSettingsManager = new AppSettingsManager(this);

        RecyclerView recyclerApps = findViewById(R.id.recyclerApps);
        Button btnSave = findViewById(R.id.btnSaveAppList);

        List<AppListAdapter.AppInfo> apps = getRecentApps();
        Map<String, AppSettingsManager.AppSetting> settings = appSettingsManager.loadAll();

        adapter = new AppListAdapter(apps, settings);
        recyclerApps.setLayoutManager(new LinearLayoutManager(this));
        recyclerApps.setAdapter(adapter);

        btnSave.setOnClickListener(v -> {
            appSettingsManager.saveAll(adapter.getSettings());
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        });
    }

    private List<AppListAdapter.AppInfo> getRecentApps() {
        Map<String, String> labelToPackage = new HashMap<>();

        if (hasUsageStatsPermission()) {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_WEEKLY, now - 7 * 24 * 60 * 60 * 1000L, now);

            if (stats != null) {
                PackageManager pm = getPackageManager();
                for (UsageStats s : stats) {
                    if (s.getTotalTimeInForeground() > 0) {
                        String pkg = s.getPackageName();
                        String label = AppUtil.getAppLabel(pm, pkg);
                        labelToPackage.put(label, pkg);
                    }
                }
            }
        }

        // Also include apps that already have settings
        Map<String, AppSettingsManager.AppSetting> existing = appSettingsManager.loadAll();
        for (String label : existing.keySet()) {
            if (!labelToPackage.containsKey(label)) {
                labelToPackage.put(label, null);
            }
        }

        List<String> sortedLabels = new ArrayList<>(labelToPackage.keySet());
        Collections.sort(sortedLabels);

        List<AppListAdapter.AppInfo> apps = new ArrayList<>();
        for (String label : sortedLabels) {
            apps.add(new AppListAdapter.AppInfo(label, labelToPackage.get(label)));
        }
        return apps;
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
