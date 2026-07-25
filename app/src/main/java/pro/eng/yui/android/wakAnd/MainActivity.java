package pro.eng.yui.android.wakAnd;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
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

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_PROJECT_NAME = "project_name";
    private static final String KEY_PROJECT_ENABLED = "project_enabled";
    private static final String KEY_LANG = "lang";
    static final String KEY_SILENT_NOTIFICATION = "silent_notification";

    private TextView txtUserName;
    private TextView txtDashboardLink;
    private TextInputEditText editApiKey;
    private TextView txtApiKeyWarning;
    private String savedApiKey = "";
    private TextInputEditText editApiUrl;

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
        Button btnSave = findViewById(R.id.btnSave);
        Button btnGrantUsageAccess = findViewById(R.id.btnGrantUsageAccess);
        Button btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        Button btnLang = findViewById(R.id.btnLang);
        Button btnAppList = findViewById(R.id.btnAppList);
        Button btnOfflineTime = findViewById(R.id.btnOfflineTime);

        loadSettings();

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
        btnEnableAccessibility.setOnClickListener(v -> {
            View titleView = LayoutInflater.from(this).inflate(R.layout.dialog_custom_title, null);
            TextView titleText = titleView.findViewById(R.id.dialogTitle);
            titleText.setText(R.string.accessibility_disclosure_title);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setCustomTitle(titleView)
                    .setMessage(R.string.accessibility_disclosure_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.agree, (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton(R.string.disagree, null)
                    .show();
        });
        btnAppList.setOnClickListener(v ->
                startActivity(new Intent(this, AppListActivity.class)));
        btnOfflineTime.setOnClickListener(v ->
                startActivity(new Intent(this, OfflineTimeActivity.class)));
        btnLang.setOnClickListener(v -> toggleLanguage());

        SwitchMaterial switchSilent = findViewById(R.id.switchSilentNotification);
        SharedPreferences prefsForSwitch = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        switchSilent.setChecked(prefsForSwitch.getBoolean(KEY_SILENT_NOTIFICATION, true));
        switchSilent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_SILENT_NOTIFICATION, isChecked).apply();
            if (isChecked) {
                requestNotificationPermissionAndStart();
            } else {
                stopService(new Intent(this, TrackingService.class));
            }
        });
    }


    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        savedApiKey = prefs.getString(KEY_API_KEY, "");
        editApiKey.setText(savedApiKey);
        txtApiKeyWarning.setVisibility(android.view.View.GONE);
        editApiUrl.setText(prefs.getString(KEY_API_URL, WakaTimeClient.getDefaultApiUrl()));
        fetchAndDisplayUser();
    }

    private void fetchAndDisplayUser() {
        WakaTimeClient client = new WakaTimeClient(this);
        client.fetchCurrentUser((displayName, dashboardUrl, profileUrl) -> runOnUiThread(() -> {
            if (displayName != null && !displayName.isEmpty()) {
                txtUserName.setText(displayName);
                if (profileUrl != null) {
                    txtUserName.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl));
                        startActivity(intent);
                    });
                } else {
                    txtUserName.setOnClickListener(null);
                    txtUserName.setClickable(false);
                }
                txtDashboardLink.setVisibility(android.view.View.VISIBLE);
                txtDashboardLink.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl));
                    startActivity(intent);
                });
            } else {
                txtUserName.setText(R.string.user_not_linked);
                txtUserName.setOnClickListener(null);
                txtUserName.setClickable(false);
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


    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                startService(new Intent(this, TrackingService.class));
            });

    private void requestNotificationPermissionAndStart() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                startService(new Intent(this, TrackingService.class));
            }
        } else {
            startService(new Intent(this, TrackingService.class));
        }
    }

}
