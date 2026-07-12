package pro.eng.yui.android.wakAnd;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BrowserConfig {

    private static final Map<String, String> BROWSER_URL_BAR_IDS;
    private static final List<String> BROWSER_PACKAGES;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("com.android.chrome", "com.android.chrome:id/url_bar");
        map.put("org.mozilla.firefox", "org.mozilla.firefox:id/url_bar_title");
        map.put("org.mozilla.firefox_beta", "org.mozilla.firefox_beta:id/url_bar_title");
        map.put("org.mozilla.fenix", "org.mozilla.fenix:id/url_bar_title");
        map.put("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar");
        map.put("com.opera.browser", "com.opera.browser:id/url_field");
        map.put("com.opera.mini.native", "com.opera.mini.native:id/url_field");
        map.put("com.brave.browser", "com.brave.browser:id/url_bar");
        map.put("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser:id/location_bar_edit_text");
        map.put("com.vivaldi.browser", "com.vivaldi.browser:id/url_bar");
        map.put("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput");
        BROWSER_URL_BAR_IDS = Collections.unmodifiableMap(map);
        BROWSER_PACKAGES = Collections.unmodifiableList(Arrays.asList(map.keySet().toArray(new String[0])));
    }

    public static boolean isBrowser(String packageName) {
        return packageName != null && BROWSER_URL_BAR_IDS.containsKey(packageName);
    }

    public static String getUrlBarId(String packageName) {
        return BROWSER_URL_BAR_IDS.get(packageName);
    }

    public static String[] getAllPackages() {
        return BROWSER_PACKAGES.toArray(new String[0]);
    }
}
