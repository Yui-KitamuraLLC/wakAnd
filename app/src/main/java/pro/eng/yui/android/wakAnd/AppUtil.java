package pro.eng.yui.android.wakAnd;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * アプリケーション全体で使用される汎用的なユーティリティクラス。
 */
public class AppUtil {

    private AppUtil() {
    }

    /**
     * 指定されたパッケージ名のアプリケーション名を返します。
     * 見つからない場合はパッケージ名を返します。
     *
     * @param pm          PackageManager インスタンス
     * @param packageName 対象のパッケージ名
     * @return アプリケーションのラベル
     */
    public static String getAppLabel(PackageManager pm, String packageName) {
        if (packageName == null) {
            return "Unknown";
        }
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /**
     * 指定されたパッケージ名のアプリケーションアイコンを返します。
     * 見つからない場合はデフォルトのアイコンを返します。
     *
     * @param pm          PackageManager インスタンス
     * @param packageName 対象のパッケージ名
     * @return アプリケーションのアイコン
     */
    public static Drawable getAppIcon(PackageManager pm, String packageName) {
        try {
            if (packageName != null) {
                return pm.getApplicationIcon(packageName);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return pm.getDefaultActivityIcon();
    }

    /**
     * 指定されたアクセシビリティサービスが有効かどうかを確認します。
     *
     * @param context コンテキスト
     * @param service 確認するサービスの名前（クラス）
     * @return 有効な場合は true、そうでない場合は false
     */
    public static boolean isAccessibilityServiceEnabled(Context context, Class<? extends AccessibilityService> service) {
        ComponentName expectedComponentName = new ComponentName(context, service);
        String enabledServicesSetting = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) {
            return false;
        }
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);
        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledService = ComponentName.unflattenFromString(componentNameString);
            if (enabledService != null && enabledService.equals(expectedComponentName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * アプリの設定に基づいて、ローカライズされた文字列リソースを取得するためのコンテキストを返します。
     *
     * @param context 元のコンテキスト
     * @return ローカライズ設定が適用された新しいコンテキスト
     */
    public static Context getLocalizedContext(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("wakand_prefs", Context.MODE_PRIVATE);
        String lang = prefs.getString("lang", "ja");
        java.util.Locale locale = java.util.Locale.forLanguageTag(lang);
        java.util.Locale.setDefault(locale);
        android.content.res.Configuration config = new android.content.res.Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
