package pro.eng.yui.android.wakAnd;

import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class OAuthManagerTest {
    private static final String ID = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String SECOND_ID = "BBBBBBBBBBBBBBBBBBBBBA";
    private static class MemoryStore implements OAuthManager.StateStore {
        JSONObject value = new JSONObject();
        @Override public JSONObject read() throws Exception { return new JSONObject(value.toString()); }
        @Override public void write(JSONObject next) throws Exception { value = new JSONObject(next.toString()); }
    }
    private static JSONObject token(long generation) throws Exception {
        return new JSONObject().put("access_token", "fake-access-" + generation).put("token_type", "Bearer")
                .put("expires_at", System.currentTimeMillis()/1000 + 3600).put("generation", generation)
                .put("granted_scope", "read_stats.projects,write_heartbeats");
    }
    private static JSONObject start(String id) throws Exception {
        return new JSONObject().put("link_id", id).put("expires_at", System.currentTimeMillis()/1000 + 600)
                .put("browser_url", OAuthPolicy.AUTH_BASE + "oauth/launch?link_id=" + id + "&launch_token=" + OAuthPolicy.randomSecret());
    }
    @Test public void lostCompleteResponseKeepsPendingAndSurvivesNewManager() throws Exception {
        MemoryStore store = new MemoryStore();
        OAuthManager manager = new OAuthManager(store, (path, body, credentials) -> {
            try { if (path.equals("v1/link/start")) return start(ID); }
            catch (Exception error) { throw new AssertionError(error); }
            throw new OAuthManager.Failure("network_unavailable");
        });
        manager.begin();
        try { manager.complete(); fail(); } catch (OAuthManager.Failure expected) { assertEquals("network_unavailable", expected.code); }
        assertEquals("pending", manager.status());
        OAuthManager restarted = new OAuthManager(store, (path, body, credentials) -> {
            try { return token(1); } catch (Exception error) { throw new AssertionError(error); }
        });
        assertTrue(restarted.acceptsCompletion(OAuthPolicy.AUTH_BASE + "app/complete?link_id=" + ID));
        restarted.complete(); assertEquals("active", restarted.status()); assertEquals(1, restarted.generation());
        assertFalse(store.value.getJSONObject("active").has("refresh_token"));
    }
    @Test public void disconnectCommitsBeforeInFlightRefreshReturns() throws Exception {
        MemoryStore store = new MemoryStore();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OAuthManager manager = new OAuthManager(store, (path, body, credentials) -> {
            try {
                if (path.equals("v1/link/start")) return start(ID);
                if (path.equals("v1/token/refresh")) { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); return token(2); }
                return token(1);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        manager.begin(); manager.complete();
        AtomicReference<String> result = new AtomicReference<>();
        Thread refresh = new Thread(() -> {
            try { manager.accessToken("https://api.wakatime.com/api/v1", true, 1); result.set("returned-token"); }
            catch (OAuthManager.Failure error) { result.set(error.code); }
        });
        refresh.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            new OAuthManager(store, null).stopLocal();
            assertEquals("disconnect_pending", manager.status());
            assertFalse(store.value.has("active"));
            assertFalse(store.value.getJSONArray("revocations").getJSONObject(0).has("access_token"));
        } finally { release.countDown(); refresh.join(5000); }
        assertFalse(refresh.isAlive()); assertEquals("link_disconnected", result.get()); assertFalse(store.value.has("active"));
    }
    @Test public void reconnectSwitchesAtomicallyAndRetainsFailedRevocation() throws Exception {
        MemoryStore store = new MemoryStore(); final int[] starts = {0};
        OAuthManager manager = new OAuthManager(store, (path, body, credentials) -> {
            if (path.equals("v1/link/disconnect")) throw new OAuthManager.Failure("network_unavailable");
            try { return path.equals("v1/link/start") ? start(starts[0]++ == 0 ? ID : SECOND_ID) : token(1); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        manager.begin(); manager.complete(); manager.begin();
        assertEquals(ID, store.value.getJSONObject("active").getString("link_id"));
        try { manager.complete(); fail(); } catch (OAuthManager.Failure expected) { assertEquals("network_unavailable", expected.code); }
        assertEquals(SECOND_ID, store.value.getJSONObject("active").getString("link_id"));
        assertEquals(ID, store.value.getJSONArray("revocations").getJSONObject(0).getString("link_id"));
        try { manager.disconnect(); fail(); } catch (OAuthManager.Failure expected) { assertEquals("network_unavailable", expected.code); }
        assertEquals("disconnect_pending", manager.status()); assertFalse(store.value.has("active"));
        assertEquals(2, store.value.getJSONArray("revocations").length());
    }
    @Test public void customHostNeverGetsAnOAuthCredential() throws Exception {
        MemoryStore store = new MemoryStore();
        OAuthManager manager = new OAuthManager(store, (path, body, credentials) -> { throw new AssertionError("no request allowed"); });
        try { manager.accessToken("https://wakapi.example/api/v1", false, 0); fail(); }
        catch (OAuthManager.Failure expected) { assertEquals("invalid_destination", expected.code); }
    }
}
