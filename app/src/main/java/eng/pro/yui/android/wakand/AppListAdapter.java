package eng.pro.yui.android.wakand;

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

import java.util.List;
import java.util.Map;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private static final String[] CATEGORIES = {
            "designing", "browsing", "communicating", "coding",
            "debugging", "learning", "manual testing", "running tests",
            "writing docs", "building", "indexing", "researching"
    };

    private final List<AppInfo> apps;
    private final Map<String, AppSettingsManager.AppSetting> settings;

    public static class AppInfo {
        public final String label;
        public final String packageName;

        public AppInfo(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    public AppListAdapter(List<AppInfo> apps, Map<String, AppSettingsManager.AppSetting> settings) {
        this.apps = apps;
        this.settings = settings;
    }

    public Map<String, AppSettingsManager.AppSetting> getSettings() {
        return settings;
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
            setting = new AppSettingsManager.AppSetting(false, "AndroidUsage", "designing");
            settings.put(appName, setting);
        }

        holder.txtAppName.setText(appName);

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
                holder.itemView.getContext(),
                android.R.layout.simple_spinner_item,
                CATEGORIES
        );
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        holder.spinnerCategory.setAdapter(categoryAdapter);

        // Remove listeners before setting values to avoid triggering
        holder.switchApp.setOnCheckedChangeListener(null);

        holder.switchApp.setChecked(setting.enabled);
        holder.layoutAppSettings.setVisibility(setting.enabled ? View.VISIBLE : View.GONE);
        holder.editAppProject.setText(setting.projectName);

        int catIndex = 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(setting.category)) {
                catIndex = i;
                break;
            }
        }
        holder.spinnerCategory.setSelection(catIndex);

        final AppSettingsManager.AppSetting finalSetting = setting;

        holder.switchApp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            finalSetting.enabled = isChecked;
            holder.layoutAppSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        holder.editAppProject.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String text = String.valueOf(holder.editAppProject.getText());
                finalSetting.projectName = text.isEmpty() ? "AndroidUsage" : text;
            }
        });

        holder.spinnerCategory.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                finalSetting.category = CATEGORIES[pos];
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
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
        final SwitchMaterial switchApp;
        final View layoutAppSettings;
        final TextInputEditText editAppProject;
        final Spinner spinnerCategory;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
            txtAppName = itemView.findViewById(R.id.txtAppName);
            switchApp = itemView.findViewById(R.id.switchApp);
            layoutAppSettings = itemView.findViewById(R.id.layoutAppSettings);
            editAppProject = itemView.findViewById(R.id.editAppProject);
            spinnerCategory = itemView.findViewById(R.id.spinnerCategory);
        }
    }
}
