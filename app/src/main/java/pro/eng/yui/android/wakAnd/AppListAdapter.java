package pro.eng.yui.android.wakAnd;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private final List<AppInfo> apps;
    private final Map<String, AppSettingsManager.AppSetting> settings;
    private List<String> apiProjects = new ArrayList<>();
    private final Set<String> newProjects = new LinkedHashSet<>();
    private String newProjectLabel = "+ New Project";

    public static class AppInfo {
        public final String label;
        public final String packageName;
        public final long installTime;

        public AppInfo(String label, String packageName, long installTime) {
            this.label = label;
            this.packageName = packageName;
            this.installTime = installTime;
        }
    }

    public AppListAdapter(List<AppInfo> apps, Map<String, AppSettingsManager.AppSetting> settings) {
        this.apps = apps;
        this.settings = settings;
    }

    public Map<String, AppSettingsManager.AppSetting> getSettings() {
        return settings;
    }

    public void setApiProjects(List<String> projects) {
        this.apiProjects = projects != null ? projects : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setNewProjectLabel(String label) {
        this.newProjectLabel = label;
    }

    public void updateList(List<AppInfo> newApps) {
        apps.clear();
        apps.addAll(newApps);
        notifyDataSetChanged();
    }

    private List<String> buildProjectList() {
        List<String> list = new ArrayList<>();
        list.add(newProjectLabel);
        for (String p : newProjects) {
            if (!apiProjects.contains(p)) {
                list.add(p);
            }
        }
        list.addAll(apiProjects);
        return list;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo appInfo = apps.get(position);
        String appName = appInfo.label;
        AppSettingsManager.AppSetting setting = settings.get(appName);

        // Load app icon
        if (appInfo.packageName != null) {
            try {
                PackageManager pm = holder.itemView.getContext().getPackageManager();
                Drawable icon = pm.getApplicationIcon(appInfo.packageName);
                holder.imgAppIcon.setImageDrawable(icon);
            } catch (PackageManager.NameNotFoundException e) {
                holder.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        } else {
            holder.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        if (setting == null) {
            setting = new AppSettingsManager.AppSetting(false, "AndroidUsage");
            settings.put(appName, setting);
        }

        holder.txtAppName.setText(appName);

        // Format install date
        if (appInfo.installTime > 0) {
            long now = System.currentTimeMillis();
            long threeDaysMs = TimeUnit.DAYS.toMillis(3);
            SimpleDateFormat fmt;
            if (now - appInfo.installTime < threeDaysMs) {
                fmt = new SimpleDateFormat("M/d H:mm", Locale.getDefault());
            } else {
                fmt = new SimpleDateFormat("yyyy/M/d", Locale.getDefault());
            }
            holder.txtInstallDate.setText(fmt.format(new Date(appInfo.installTime)));
            holder.txtInstallDate.setVisibility(View.VISIBLE);
        } else {
            holder.txtInstallDate.setVisibility(View.GONE);
        }

        // Project spinner
        List<String> projectList = buildProjectList();
        ArrayAdapter<String> projectAdapter = new ArrayAdapter<>(
                holder.itemView.getContext(),
                android.R.layout.simple_spinner_item,
                projectList
        );
        projectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        holder.spinnerProject.setAdapter(projectAdapter);

        // Find current project in list
        String currentProject = setting.projectName;
        int projectIndex = projectList.indexOf(currentProject);
        if (projectIndex < 0) {
            // Current project not in list — treat as "new project" mode
            projectIndex = 0;
        }

        holder.spinnerProject.setOnItemSelectedListener(null);
        holder.spinnerProject.setSelection(projectIndex);

        // Show/hide new project input
        boolean isNewProject = (projectIndex == 0);
        holder.layoutNewProject.setVisibility(isNewProject ? View.VISIBLE : View.GONE);
        if (isNewProject) {
            holder.editNewProjectName.setText(currentProject);
        }

        // Remove listeners before setting values to avoid triggering
        holder.switchApp.setOnCheckedChangeListener(null);

        holder.switchApp.setChecked(setting.enabled);
        holder.layoutAppSettings.setVisibility(setting.enabled ? View.VISIBLE : View.GONE);

        final AppSettingsManager.AppSetting finalSetting = setting;

        holder.switchApp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            finalSetting.enabled = isChecked;
            holder.layoutAppSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        holder.spinnerProject.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) {
                    // "New Project" selected
                    holder.layoutNewProject.setVisibility(View.VISIBLE);
                    holder.editNewProjectName.setText(finalSetting.projectName);
                } else {
                    holder.layoutNewProject.setVisibility(View.GONE);
                    String selected = projectList.get(pos);
                    finalSetting.projectName = selected;
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        holder.editNewProjectName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String text = String.valueOf(holder.editNewProjectName.getText()).trim();
                if (!text.isEmpty()) {
                    finalSetting.projectName = text;
                    if (!apiProjects.contains(text)) {
                        newProjects.add(text);
                    }
                } else {
                    finalSetting.projectName = "AndroidUsage";
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgAppIcon;
        final TextView txtAppName;
        final TextView txtInstallDate;
        final SwitchMaterial switchApp;
        final View layoutAppSettings;
        final Spinner spinnerProject;
        final View layoutNewProject;
        final TextInputEditText editNewProjectName;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
            txtAppName = itemView.findViewById(R.id.txtAppName);
            txtInstallDate = itemView.findViewById(R.id.txtInstallDate);
            switchApp = itemView.findViewById(R.id.switchApp);
            layoutAppSettings = itemView.findViewById(R.id.layoutAppSettings);
            spinnerProject = itemView.findViewById(R.id.spinnerProject);
            layoutNewProject = itemView.findViewById(R.id.layoutNewProject);
            editNewProjectName = itemView.findViewById(R.id.editNewProjectName);
        }
    }
}
