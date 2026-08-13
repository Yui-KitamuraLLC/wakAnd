package pro.eng.yui.android.wakAnd;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

        // Package Name Input
        EditText editPackageName = findViewById(R.id.editPackageName);
        Button btnAddPackage = findViewById(R.id.btnAddPackage);

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

        btnAddPackage.setOnClickListener(v -> {
            String pkg = editPackageName.getText().toString().trim();
            if (pkg.isEmpty()) return;
            addPackageToList(pkg);
            editPackageName.setText("");
        });

        btnSave.setOnClickListener(v -> {
            appSettingsManager.saveAll(adapter.getSettings());
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void addPackageToList(String pkg) {
        PackageManager pm = getPackageManager();
        String label = pkg;
        long installTime = 0;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            installTime = pi.firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            // Package info not accessible, but we allow adding by package name
        }

        // Check if already in list
        for (AppListAdapter.AppInfo app : allApps) {
            if (pkg.equals(app.packageName)) {
                Toast.makeText(this, R.string.already_exists, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        AppListAdapter.AppInfo newApp = new AppListAdapter.AppInfo(label, pkg, installTime);
        allApps.add(newApp);
        adapter.updateList(new ArrayList<>(allApps));
        Toast.makeText(this, R.string.added, Toast.LENGTH_SHORT).show();
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
                    AppSettingsManager.AppSetting sa = getSettingFor(a, settings);
                    AppSettingsManager.AppSetting sb = getSettingFor(b, settings);
                    boolean ea = sa != null && sa.enabled;
                    boolean eb = sb != null && sb.enabled;
                    if (ea != eb) return ea ? -1 : 1;
                    return a.label.compareToIgnoreCase(b.label);
                });
                break;
            case 3: // Project name A-Z
                Collections.sort(sorted, (a, b) -> {
                    AppSettingsManager.AppSetting sa = getSettingFor(a, settings);
                    AppSettingsManager.AppSetting sb = getSettingFor(b, settings);
                    String pa = sa != null ? sa.projectName : "AndroidUsage";
                    String pb = sb != null ? sb.projectName : "AndroidUsage";
                    return pa.compareToIgnoreCase(pb);
                });
                break;
        }
        adapter.updateList(sorted);
    }

    private AppSettingsManager.AppSetting getSettingFor(AppListAdapter.AppInfo info, Map<String, AppSettingsManager.AppSetting> settings) {
        if (info.packageName != null && settings.containsKey(info.packageName)) {
            return settings.get(info.packageName);
        }
        return settings.get(info.label);
    }

    private List<AppListAdapter.AppInfo> getRecentApps() {
        Map<String, AppListAdapter.AppInfo> packageToInfo = new HashMap<>();
        PackageManager pm = getPackageManager();

        if (hasUsageStatsPermission()) {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_WEEKLY, now - 7 * 24 * 60 * 60 * 1000L, now);

            if (stats != null) {
                for (UsageStats s : stats) {
                    if (s.getTotalTimeInForeground() > 0) {
                        String pkg = s.getPackageName();
                        if (packageToInfo.containsKey(pkg)) continue;

                        String label = AppUtil.getAppLabel(pm, pkg);
                        long installTime = 0;
                        try {
                            PackageInfo pi = pm.getPackageInfo(pkg, 0);
                            installTime = pi.firstInstallTime;
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                        packageToInfo.put(pkg, new AppListAdapter.AppInfo(label, pkg, installTime));
                    }
                }
            }
        }

        // Also include apps that already have settings
        Map<String, AppSettingsManager.AppSetting> existing = appSettingsManager.loadAll();
        for (Map.Entry<String, AppSettingsManager.AppSetting> entry : existing.entrySet()) {
            String key = entry.getKey();
            AppSettingsManager.AppSetting s = entry.getValue();
            String pkg = s.packageName != null ? s.packageName : (key.contains(".") ? key : null);
            String label = s.label != null ? s.label : key;

            if (pkg != null && !packageToInfo.containsKey(pkg)) {
                long installTime = 0;
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    installTime = pi.firstInstallTime;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                packageToInfo.put(pkg, new AppListAdapter.AppInfo(label, pkg, installTime));
            } else if (pkg == null) {
                // Legacy or unknown, but we still want to show it if enabled
                // However, without package name we can't do much.
                // For now, skip if no package name and not in recent apps.
            }
        }

        List<AppListAdapter.AppInfo> apps = new ArrayList<>(packageToInfo.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

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
