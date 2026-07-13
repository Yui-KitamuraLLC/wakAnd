package pro.eng.yui.android.wakAnd;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class BrowserUrlAccessibilityService extends AccessibilityService {

    private static volatile String lastUrl = "";
    private static volatile String lastBrowserPackage = "";

    public static String getLastUrl() {
        return lastUrl;
    }

    public static String getLastBrowserPackage() {
        return lastBrowserPackage;
    }

    /**
     * Reset URL when switching away from a browser.
     */
    public static void clearIfNotBrowser(String packageName) {
        if (!BrowserConfig.isBrowser(packageName)) {
            lastUrl = "";
            lastBrowserPackage = "";
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        if (url.isEmpty()) return "";
        return url;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        // Handle case where packageName might be null in some events
        CharSequence eventPackage = event.getPackageName();
        if (eventPackage == null) {
            return;
        }

        String packageName = eventPackage.toString();
        if (!BrowserConfig.isBrowser(packageName)) {
            return;
        }

        lastBrowserPackage = packageName;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            root = event.getSource();
        }
        if (root == null) {
            return;
        }

        String urlBarId = BrowserConfig.getUrlBarId(packageName);
        if (urlBarId == null) {
            if (root != event.getSource()) root.recycle();
            return;
        }

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(urlBarId);
        // Fallback for Chrome if primary ID fails
        if ((nodes == null || nodes.isEmpty()) && "com.android.chrome".equals(packageName)) {
            nodes = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/location_bar_edit_text");
            if (nodes == null || nodes.isEmpty()) {
                nodes = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_edit_text");
            }
        }

        if (nodes != null && !nodes.isEmpty()) {
            AccessibilityNodeInfo urlNode = nodes.get(0);
            if (urlNode.getText() != null) {
                String currentText = normalizeUrl(urlNode.getText().toString());
                // Basic cleanup of URL text
                if (currentText.startsWith("http")) {
                    lastUrl = currentText;
                } else if (currentText.contains(".")) {
                    // It might be just the host or domain name without protocol
                    lastUrl = currentText;
                } else if (!currentText.isEmpty()) {
                    // Fallback or potentially incomplete URL, but still update if it's different
                    lastUrl = currentText;
                }
            }
            urlNode.recycle();
            // Also recycle other nodes if list has more
            for (int i = 1; i < nodes.size(); i++) {
                nodes.get(i).recycle();
            }
        } else {
            // URL bar not found (e.g. incognito mode) — clear URL
            lastUrl = "";
        }
        if (root != event.getSource()) {
            root.recycle();
        }
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
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.packageNames = BrowserConfig.getAllPackages();
        info.notificationTimeout = 100; // Faster update
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }
}
