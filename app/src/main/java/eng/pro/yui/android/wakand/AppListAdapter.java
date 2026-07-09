package eng.pro.yui.android.wakand;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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

    private final List<String> appNames;
    private final Map<String, AppSettingsManager.AppSetting> settings;

    public AppListAdapter(List<String> appNames, Map<String, AppSettingsManager.AppSetting> settings) {
        this.appNames = appNames;
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
        String appName = appNames.get(position);
        AppSettingsManager.AppSetting setting = settings.get(appName);
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
        return appNames.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtAppName;
        final SwitchMaterial switchApp;
        final View layoutAppSettings;
        final TextInputEditText editAppProject;
        final Spinner spinnerCategory;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            txtAppName = itemView.findViewById(R.id.txtAppName);
            switchApp = itemView.findViewById(R.id.switchApp);
            layoutAppSettings = itemView.findViewById(R.id.layoutAppSettings);
            editAppProject = itemView.findViewById(R.id.editAppProject);
            spinnerCategory = itemView.findViewById(R.id.spinnerCategory);
        }
    }
}
