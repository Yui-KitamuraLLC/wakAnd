package pro.eng.yui.android.wakAnd;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppListActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_LANG = "lang";

    private AppSettingsManager appSettingsManager;
    private AppListAdapter adapter;
    private List<AppListAdapter.AppInfo> allApps;
    private WakaTimeClient wakaTimeClient;

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
        Spinner spinnerSort = findViewById(R.id.spinnerSort);

        String[] sortOptions = {
                getString(R.string.sort_by_name),
                getString(R.string.sort_by_install),
                getString(R.string.sort_by_enabled),
                getString(R.string.sort_by_project)
        };
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sortOptions);
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSort.setAdapter(sortAdapter);

        allApps = getRecentApps();
        Map<String, AppSettingsManager.AppSetting> settings = appSettingsManager.loadAll();

        adapter = new AppListAdapter(new ArrayList<>(allApps), settings);
        adapter.setNewProjectLabel(getString(R.string.new_project));
        recyclerApps.setLayoutManager(new LinearLayoutManager(this));
        recyclerApps.setAdapter(adapter);

        wakaTimeClient = new WakaTimeClient(this);
        wakaTimeClient.fetchProjects(projects -> runOnUiThread(() -> adapter.setApiProjects(projects)));

        spinnerSort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                sortAndUpdate(position);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        btnSave.setOnClickListener(v -> {
            appSettingsManager.saveAll(adapter.getSettings());
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void sortAndUpdate(int sortType) {
        List<AppListAdapter.AppInfo> sorted = new ArrayList<>(allApps);
        Map<String, AppSettingsManager.AppSetting> settings = adapter.getSettings();
        switch (sortType) {
            case 0: // App name
                Collections.sort(sorted, Comparator.comparing(a -> a.label.toLowerCase()));
                break;
            case 1: // Install date (newest first)
                Collections.sort(sorted, Comparator.comparingLong((AppListAdapter.AppInfo a) -> a.installTime).reversed());
                break;
            case 2: // Enabled first, then by name
                Collections.sort(sorted, (a, b) -> {
                    AppSettingsManager.AppSetting sa = settings.get(a.label);
                    AppSettingsManager.AppSetting sb = settings.get(b.label);
                    boolean ea = sa != null && sa.enabled;
                    boolean eb = sb != null && sb.enabled;
                    if (ea != eb) return ea ? -1 : 1;
                    return a.label.compareToIgnoreCase(b.label);
                });
                break;
            case 3: // Project name A-Z
                Collections.sort(sorted, (a, b) -> {
                    AppSettingsManager.AppSetting sa = settings.get(a.label);
                    AppSettingsManager.AppSetting sb = settings.get(b.label);
                    String pa = sa != null ? sa.projectName : "AndroidUsage";
                    String pb = sb != null ? sb.projectName : "AndroidUsage";
                    return pa.compareToIgnoreCase(pb);
                });
                break;
        }
        adapter.updateList(sorted);
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

        PackageManager pm = getPackageManager();
        List<String> sortedLabels = new ArrayList<>(labelToPackage.keySet());
        Collections.sort(sortedLabels);

        List<AppListAdapter.AppInfo> apps = new ArrayList<>();
        for (String label : sortedLabels) {
            String pkg = labelToPackage.get(label);
            long installTime = 0;
            if (pkg != null) {
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    installTime = pi.firstInstallTime;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            apps.add(new AppListAdapter.AppInfo(label, pkg, installTime));
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
