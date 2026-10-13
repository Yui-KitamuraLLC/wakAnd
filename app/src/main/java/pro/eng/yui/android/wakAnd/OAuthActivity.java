package pro.eng.yui.android.wakAnd;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Official authorization is independent of the retained custom host settings. */
public class OAuthActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private OAuthManager manager;
    private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); manager = new OAuthManager(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        status = new TextView(this); status.setText(R.string.oauth_checking); layout.addView(status);
        Button connect = new Button(this); connect.setText(R.string.oauth_connect); layout.addView(connect);
        Button check = new Button(this); check.setText(R.string.oauth_check); layout.addView(check);
        Button disconnect = new Button(this); disconnect.setText(R.string.oauth_disconnect); layout.addView(disconnect);
        setContentView(layout);
        connect.setOnClickListener(view -> execute(() -> {
            String url = manager.begin();
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); });
        }));
        check.setOnClickListener(view -> execute(() -> { manager.complete(); manager.drainRevocations(); }));
        disconnect.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.oauth_disconnect_confirm).setPositiveButton(R.string.yes, (dialog, which) -> {
                    try { manager.stopLocal(); execute(manager::drainRevocations); }
                    catch (OAuthManager.Failure error) { status.setText(R.string.oauth_connection_failed); }
                })
                .setNegativeButton(R.string.no, null).show());
    }
    @Override protected void onResume() {
        super.onResume();
        String callback = getIntent().getDataString();
        execute(() -> {
            if (callback != null && !manager.acceptsCompletion(callback)) throw new OAuthManager.Failure("invalid_callback");
            if ("pending".equals(manager.status())) manager.complete();
            manager.drainRevocations();
        });
        getIntent().setData(null);
    }
    private interface Action { void run() throws OAuthManager.Failure; }
    private void execute(Action action) {
        status.setText(R.string.oauth_checking);
        executor.execute(() -> {
            int errorMessage = 0;
            try { action.run(); }
            catch (OAuthManager.Failure error) {
                switch (error.code) {
                    case "authorization_pending": errorMessage = R.string.oauth_pending; break;
                    case "reauth_required": case "link_expired": case "link_disconnected": case "device_unauthorized": errorMessage = R.string.oauth_reauth; break;
                    case "invalid_callback": errorMessage = R.string.oauth_invalid_callback; break;
                    case "operation_in_progress": case "rate_limited": errorMessage = R.string.oauth_retry_later; break;
                    default: errorMessage = R.string.oauth_connection_failed;
                }
            }
            String state = manager.status(); int message = errorMessage;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                int resource;
                switch (state) {
                    case "active": resource = R.string.oauth_active; break;
                    case "active_confirmation_required": resource = R.string.oauth_active_confirmation; break;
                    case "pending": resource = R.string.oauth_pending; break;
                    case "reauth_required": resource = R.string.oauth_reauth; break;
                    case "disconnect_pending": resource = R.string.oauth_disconnect_pending; break;
                    case "provider_confirmation_required": resource = R.string.oauth_provider_confirmation; break;
                    default: resource = R.string.oauth_unlinked;
                }
                status.setText(message == 0 ? getString(resource) : getString(resource) + "\n" + getString(message));
                org.json.JSONObject info = manager.authorizationInfo();
                if (info.has("expires_at")) {
                    java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", java.util.Locale.US);
                    String expiry = format.format(new java.util.Date(info.optLong("expires_at") * 1000));
                    status.append("\n" + getString(R.string.oauth_permissions, info.optString("granted_scope"))
                            + "\n" + getString(R.string.oauth_expiry, expiry));
                }
            });
        });
    }
    @Override protected void onDestroy() { executor.shutdown(); super.onDestroy(); }
}
