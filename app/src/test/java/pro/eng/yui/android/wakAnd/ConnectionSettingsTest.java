package pro.eng.yui.android.wakAnd;

import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ConnectionSettingsTest {
    private static final class Preferences {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "contains": return values.containsKey(args[0]);
                        case "getString": case "getLong": return values.getOrDefault(args[0], args[1]);
                        case "edit": return editor();
                        default: throw new AssertionError(method.getName());
                    }
                });
        Object editor() {
            Map<String, Object> pending = new HashMap<>();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("put")) { pending.put((String)args[0], args[1]); return proxy; }
                        if (method.getName().equals("apply") || method.getName().equals("commit")) { values.putAll(pending); return true; }
                        throw new AssertionError(method.getName());
                    });
        }
    }
    @Test public void officialMigrationRetainsOriginalAndSeparatesCustomKey() {
        Preferences memory = new Preferences(); memory.values.put("api_url", "https://wakatime.com/api/v1");
        memory.values.put("api_key", "official-old-key"); memory.values.put("app_settings", "retained-app-settings");
        ConnectionSettings settings = new ConnectionSettings(memory.prefs);
        assertTrue(settings.snapshot().official); assertEquals("official-old-key", settings.snapshot().apiKey);
        assertEquals("https://wakatime.com/api/v1", memory.values.get("api_url"));
        settings.select(false); settings.saveCustom("https://wakapi.example/api", "custom-key");
        assertEquals("custom-key", settings.snapshot().apiKey);
        settings.select(true); assertEquals("official-old-key", settings.snapshot().apiKey);
        assertEquals("custom-key", memory.values.get("api_key")); assertEquals("retained-app-settings", memory.values.get("app_settings"));
    }
    @Test public void customMigrationNeverSendsItsKeyToOfficialService() {
        Preferences memory = new Preferences(); memory.values.put("api_url", "https://wakapi.example/api");
        memory.values.put("api_key", "custom-key"); ConnectionSettings settings = new ConnectionSettings(memory.prefs);
        assertFalse(settings.snapshot().official); assertEquals("custom-key", settings.snapshot().apiKey);
        settings.select(true); assertEquals("", settings.snapshot().apiKey);
        settings.select(false); assertEquals("https://wakapi.example/api", settings.snapshot().apiUrl);
        assertEquals("custom-key", settings.snapshot().apiKey);
    }
    @Test public void switchAwayAndBackCancelsOriginalSnapshotEvenAfterRestart() {
        Preferences memory = new Preferences(); ConnectionSettings settings = new ConnectionSettings(memory.prefs);
        ApiRequestRunner.Target queued = settings.snapshot(); settings.select(false); settings.select(true);
        assertFalse(settings.current(queued));
        ConnectionSettings restarted = new ConnectionSettings(memory.prefs);
        assertTrue(restarted.snapshot().official); assertFalse(restarted.current(queued));
    }
    @Test public void keyEditInvalidatesQueuedCustomRequest() {
        Preferences memory = new Preferences(); memory.values.put("api_url", "https://wakapi.example/api");
        memory.values.put("api_key", "old-key"); ConnectionSettings settings = new ConnectionSettings(memory.prefs);
        ApiRequestRunner.Target queued = settings.snapshot(); settings.saveCustom(queued.apiUrl, "new-key");
        assertFalse(settings.current(queued)); assertEquals("new-key", settings.snapshot().apiKey);
    }
}
