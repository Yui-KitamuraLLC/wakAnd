package pro.eng.yui.android.wakAnd;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class AppUtil {

    private AppUtil() {
    }

    public static String getAppLabel(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
