package eng.pro.yui.android.wakand;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class ChromeUrlAccessibilityService extends AccessibilityService {

    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String URL_BAR_ID = "com.android.chrome:id/url_bar";

    private static volatile String lastUrl = "";

    public static String getLastUrl() {
        return lastUrl;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        String packageName = event.getPackageName().toString();
        if (!CHROME_PACKAGE.equals(packageName)) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(URL_BAR_ID);
        if (nodes != null && !nodes.isEmpty()) {
            AccessibilityNodeInfo urlNode = nodes.get(0);
            if (urlNode.getText() != null) {
                lastUrl = urlNode.getText().toString();
            }
        }
        root.recycle();
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = new String[]{CHROME_PACKAGE};
        info.notificationTimeout = 300;
        setServiceInfo(info);
    }
}
