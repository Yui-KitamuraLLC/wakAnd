package pro.eng.yui.android.wakAnd;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Fixed origin policy shared by OAuth transport and the later API integration. */
public final class OAuthPolicy {
    public static final String AUTH_BASE = "https://yui.eng.pro/android/wakAnd/";
    private OAuthPolicy() {}
    public static String randomSecret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value, boolean decodeFirst) throws Exception {
        byte[] bytes = decodeFirst ? decode(value, 32) : value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static byte[] decode(String value, int length) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("invalid_value");
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length != length || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))
            throw new IllegalArgumentException("invalid_value");
        return bytes;
    }
    public static boolean officialApi(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && ("wakatime.com".equals(uri.getHost()) || "api.wakatime.com".equals(uri.getHost()))
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && ("/api/v1".equals(uri.getRawPath()) || "/api/v1/".equals(uri.getRawPath()));
        } catch (IllegalArgumentException error) { return false; }
    }
    public static boolean completion(String url, String expectedId) {
        try {
            decode(expectedId, 16);
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "yui.eng.pro".equals(uri.getHost()) && uri.getPort() == -1
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && "/android/wakAnd/app/complete".equals(uri.getRawPath())
                    && ("link_id=" + expectedId).equals(uri.getRawQuery());
        } catch (IllegalArgumentException error) { return false; }
    }
    public static boolean launch(String url, String id) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"yui.eng.pro".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !"/android/wakAnd/oauth/launch".equals(uri.getRawPath())) return false;
            String prefix = "link_id=" + id + "&launch_token=";
            String query = uri.getRawQuery();
            if (query == null || !query.startsWith(prefix)) return false;
            decode(id, 16); decode(query.substring(prefix.length()), 32); return true;
        } catch (IllegalArgumentException error) { return false; }
    }
}
