package pro.eng.yui.android.wakAnd;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class OfflineTimeActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wakand_prefs";
    private static final String KEY_LANG = "lang";

    private Spinner spinnerProject;
    private Spinner spinnerCategory;
    private EditText editStartHour;
    private EditText editStartMinute;
    private EditText editEndHour;
    private EditText editEndMinute;
    private TextInputEditText editMeta;

    private WakaTimeClient wakaTimeClient;
    private final List<String> projects = new ArrayList<>();
    private String[] categoryValues;
    private Button btnSubmit;
    private String externalId, submittedFingerprint;

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
        setContentView(R.layout.activity_offline_time);

        spinnerProject = findViewById(R.id.spinnerProject);
        spinnerCategory = findViewById(R.id.spinnerCategory);
        editStartHour = findViewById(R.id.editStartHour);
        editStartMinute = findViewById(R.id.editStartMinute);
        editEndHour = findViewById(R.id.editEndHour);
        editEndMinute = findViewById(R.id.editEndMinute);
        editMeta = findViewById(R.id.editMeta);

        setupProjectSpinner();
        setupCategorySpinner();
        setupDefaultTimes();
        setupQuickButtons();

        if (savedInstanceState != null) {
            externalId = savedInstanceState.getString("external_id");
            submittedFingerprint = savedInstanceState.getString("submission_fingerprint");
        }
        btnSubmit = findViewById(R.id.btnSubmit);
        btnSubmit.setOnClickListener(v -> confirmAndSubmit());
    }

    private void setupProjectSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, projects);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProject.setAdapter(adapter);

        wakaTimeClient = new WakaTimeClient(this);
        findViewById(R.id.btnRetryProjects).setOnClickListener(view -> fetchProjects(adapter));
        fetchProjects(adapter);
    }

    private void fetchProjects(ArrayAdapter<String> adapter) {
        wakaTimeClient.fetchProjects(result -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            android.widget.TextView status = findViewById(R.id.txtProjectsStatus);
            if (!result.successful()) { status.setText(ApiMessages.error(result.error)); return; }
            String selected = spinnerProject.getSelectedItem() == null ? null : String.valueOf(spinnerProject.getSelectedItem());
            projects.clear();
            projects.addAll(result.value);
            adapter.notifyDataSetChanged();
            if (selected != null && projects.contains(selected)) spinnerProject.setSelection(projects.indexOf(selected));
            status.setText(projects.isEmpty() ? R.string.api_empty_projects : R.string.api_projects_loaded);
        }));
    }

    private void setupCategorySpinner() {
        categoryValues = getResources().getStringArray(R.array.offline_category_values);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.offline_category_labels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(adapter);
    }

    private void setupDefaultTimes() {
        Calendar end = Calendar.getInstance();
        end.add(Calendar.MINUTE, -1);
        setTimeFields(editEndHour, editEndMinute, end);

        Calendar start = Calendar.getInstance();
        start.add(Calendar.MINUTE, -30);
        setTimeFields(editStartHour, editStartMinute, start);
    }

    private void setupQuickButtons() {
        findViewById(R.id.btnQuick10m).setOnClickListener(v -> setStartFromEnd(10));
        findViewById(R.id.btnQuick30m).setOnClickListener(v -> setStartFromEnd(30));
        findViewById(R.id.btnQuick45m).setOnClickListener(v -> setStartFromEnd(45));
        findViewById(R.id.btnQuick1h).setOnClickListener(v -> setStartFromEnd(60));
    }

    /** Sets the start time to (end time - minutes), based on the current end fields. */
    private void setStartFromEnd(int minutes) {
        Integer endH = parseField(editEndHour, 23);
        Integer endM = parseField(editEndMinute, 59);
        if (endH == null || endM == null) {
            Toast.makeText(this, R.string.offline_invalid_time, Toast.LENGTH_SHORT).show();
            return;
        }
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, endH);
        start.set(Calendar.MINUTE, endM);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.MINUTE, -minutes);
        setTimeFields(editStartHour, editStartMinute, start);
    }

    private void setTimeFields(EditText hourField, EditText minuteField, Calendar cal) {
        hourField.setText(String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY)));
        minuteField.setText(String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE)));
    }

    /** Parses a field into an int within [0, max], or null if empty/out of range. */
    private Integer parseField(EditText field, int max) {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(text);
            if (value < 0 || value > max) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Builds a UNIX epoch (seconds) for today at the given hour:minute. */
    private double toEpochSeconds(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() / 1000.0;
    }

    private void confirmAndSubmit() {
        if (projects.isEmpty() || spinnerProject.getSelectedItem() == null) {
            Toast.makeText(this, R.string.offline_no_project, Toast.LENGTH_SHORT).show();
            return;
        }

        Integer startH = parseField(editStartHour, 23);
        Integer startM = parseField(editStartMinute, 59);
        Integer endH = parseField(editEndHour, 23);
        Integer endM = parseField(editEndMinute, 59);
        if (startH == null || startM == null || endH == null || endM == null) {
            Toast.makeText(this, R.string.offline_invalid_time, Toast.LENGTH_SHORT).show();
            return;
        }

        double startSec = toEpochSeconds(startH, startM);
        double endSec = toEpochSeconds(endH, endM);
        if (startSec >= endSec) {
            Toast.makeText(this, R.string.offline_invalid_time, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setMessage(R.string.confirm_submit_offline)
                .setPositiveButton(R.string.yes, (dialog, which) -> submit(startSec, endSec))
                .setNegativeButton(R.string.no, null)
                .show();
    }

    private void submit(double startSec, double endSec) {
        String project = String.valueOf(spinnerProject.getSelectedItem());
        int categoryIndex = spinnerCategory.getSelectedItemPosition();
        String category = (categoryIndex >= 0 && categoryIndex < categoryValues.length)
                ? categoryValues[categoryIndex] : categoryValues[0];
        String meta = editMeta.getText() != null ? editMeta.getText().toString().trim() : "";

        ConnectionSettings connections = new ConnectionSettings(this);
        ApiRequestRunner.Target target = connections.snapshot();
        String fingerprint = new org.json.JSONArray().put(target.official).put(target.apiUrl).put(target.generation)
                .put(target.official ? new OAuthManager(this).activeIdentity() : "")
                .put(project).put(category).put(meta).put(String.valueOf(startSec)).put(String.valueOf(endSec)).toString();
        if (submittedFingerprint != null && !submittedFingerprint.equals(fingerprint)) {
            Toast.makeText(this, R.string.api_submission_changed, Toast.LENGTH_LONG).show(); return;
        }
        if (externalId == null) externalId = java.util.UUID.randomUUID().toString();
        submittedFingerprint = fingerprint;
        btnSubmit.setEnabled(false);
        wakaTimeClient.sendExternalDuration(externalId, project, category, meta, startSec, endSec,
                result -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnSubmit.setEnabled(true);
                    if (result.successful()) {
                        Toast.makeText(this, R.string.offline_submit_success, Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, ApiMessages.error(result.error), Toast.LENGTH_LONG).show();
                    }
                }));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("external_id", externalId); state.putString("submission_fingerprint", submittedFingerprint);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakaTimeClient != null) {
            wakaTimeClient.shutdown();
        }
    }
}
