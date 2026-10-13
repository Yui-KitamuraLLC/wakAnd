package pro.eng.yui.android.wakAnd;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ApiRequestRunnerTest {
    private static final ApiRequestRunner.Target OFFICIAL = new ApiRequestRunner.Target(true,
            "https://api.wakatime.com/api/v1", "old-api-key", 1);
    private static class Auth implements ApiRequestRunner.Authorization {
        int refreshes, rejected;
        boolean current = true, legacy = false, expired = false;
        public ApiRequestRunner.Credential prepare(String url, String link, boolean unauthorized, long observed) {
            if (unauthorized || expired) refreshes++;
            return new ApiRequestRunner.Credential("test-token-" + refreshes, link, 1 + refreshes, unauthorized || expired);
        }
        public boolean current(ApiRequestRunner.Credential credential) { return current; }
        public void rejected(ApiRequestRunner.Credential credential) { rejected++; }
        public boolean legacyAllowed() { return legacy; }
    }
    private static void failure(String expected, ApiRequestRunner runner, ApiRequestRunner.Target target,
                                String link, boolean legacy, byte[] body) throws Exception {
        try { runner.execute(target, link, legacy, "/users/current", body); fail("Expected failure"); }
        catch (ApiRequestRunner.Failure error) { assertEquals(expected, error.code); }
    }
    @Test public void unauthorizedRefreshesOnceAndRetriesIdenticalPost() throws Exception {
        Auth auth = new Auth(); AtomicInteger sent = new AtomicInteger();
        byte[] body = "{\"external_id\":\"same-operation\"}".getBytes(StandardCharsets.UTF_8);
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
            check.run(); assertSame(body, bytes); assertEquals("POST", method);
            assertEquals(OFFICIAL.apiUrl + "/users/current/external_durations", url);
            int attempt = sent.getAndIncrement(); assertEquals("Bearer test-token-" + attempt, header);
            return new ApiRequestRunner.Response(attempt == 0 ? 401 : 201, "{}");
        });
        assertEquals(201, runner.execute(OFFICIAL, "link-a", false, "/users/current/external_durations", body).status);
        assertEquals(1, auth.refreshes); assertEquals(2, sent.get()); assertEquals(0, auth.rejected);
    }
    @Test public void repeatedUnauthorizedStopsAfterOneRetry() throws Exception {
        Auth auth = new Auth(); AtomicInteger sent = new AtomicInteger();
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
            sent.incrementAndGet(); return new ApiRequestRunner.Response(401, "");
        });
        assertEquals(401, runner.execute(OFFICIAL, "link-a", false, "/users/current", null).status);
        assertEquals(2, sent.get()); assertEquals(1, auth.refreshes); assertEquals(1, auth.rejected);
    }
    @Test public void expiryRefreshUsesTheOnlyRefreshBudget() throws Exception {
        Auth auth = new Auth(); auth.expired = true; AtomicInteger sent = new AtomicInteger();
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
            sent.incrementAndGet(); return new ApiRequestRunner.Response(401, "");
        });
        assertEquals(401, runner.execute(OFFICIAL, "link-a", false, "/users/current", null).status);
        assertEquals(1, sent.get()); assertEquals(1, auth.refreshes); assertEquals(1, auth.rejected);
    }
    @Test public void forbiddenRedirectAndServerErrorDoNotRefreshOrRetry() throws Exception {
        for (int status : new int[]{403, 302, 429, 500}) {
            Auth auth = new Auth(); AtomicInteger sent = new AtomicInteger();
            ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
                sent.incrementAndGet(); return new ApiRequestRunner.Response(status, "");
            });
            assertEquals(status, runner.execute(OFFICIAL, "link-a", false, "/users/current", null).status);
            assertEquals(1, sent.get()); assertEquals(0, auth.refreshes); assertEquals(0, auth.rejected);
        }
    }
    @Test public void transportFailureNeverRetriesAndPostDeliveryIsUnknown() throws Exception {
        for (byte[] payload : new byte[][]{null, new byte[]{1}}) {
            Auth auth = new Auth(); AtomicInteger sent = new AtomicInteger();
            ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
                sent.incrementAndGet(); throw new java.io.IOException("must never escape");
            });
            failure(payload == null ? "network_unavailable" : "delivery_unknown", runner, OFFICIAL, "link-a", false, payload);
            assertEquals(1, sent.get()); assertEquals(0, auth.refreshes);
        }
    }
    @Test public void targetChangeBetweenPrepareAndSendBlocksCredentials() throws Exception {
        Auth auth = new Auth(); AtomicInteger checks = new AtomicInteger();
        ApiRequestRunner runner = new ApiRequestRunner(target -> checks.getAndIncrement() == 0, auth,
                (url, method, bytes, header, check) -> { throw new AssertionError("Must not send"); });
        failure("destination_changed", runner, OFFICIAL, "link-a", false, null);
    }
    @Test public void changeWhileTransportOpensAlsoBlocksSend() throws Exception {
        Auth auth = new Auth(); final boolean[] current = {true};
        ApiRequestRunner runner = new ApiRequestRunner(target -> current[0], auth, (url, method, bytes, header, check) -> {
            current[0] = false; check.run(); throw new AssertionError("Must not send");
        });
        failure("destination_changed", runner, OFFICIAL, "link-a", false, null);
    }
    @Test public void staleResponseCannotPopulateNewDestination() throws Exception {
        Auth auth = new Auth(); final boolean[] current = {true};
        ApiRequestRunner runner = new ApiRequestRunner(target -> current[0], auth, (url, method, bytes, header, check) -> {
            current[0] = false; return new ApiRequestRunner.Response(200, "old account data");
        });
        failure("destination_changed", runner, OFFICIAL, "link-a", false, null);
    }
    @Test public void disconnectAfterAuthorizationBlocksSend() throws Exception {
        Auth auth = new Auth(); auth.current = false;
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth,
                (url, method, bytes, header, check) -> { throw new AssertionError("Must not send"); });
        failure("link_changed", runner, OFFICIAL, "link-a", false, null);
    }
    @Test public void customHostUsesOnlyItsBasicKeyEvenOnUnauthorized() throws Exception {
        Auth auth = new Auth() {
            public ApiRequestRunner.Credential prepare(String url, String link, boolean unauthorized, long generation) {
                throw new AssertionError("No OAuth call for custom host");
            }
        };
        ApiRequestRunner.Target custom = new ApiRequestRunner.Target(false, "https://wakapi.example/api", "custom-key", 2);
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
            assertEquals("Basic Y3VzdG9tLWtleQ==", header); return new ApiRequestRunner.Response(401, "");
        });
        assertEquals(401, runner.execute(custom, "ignored-official-link", false, "/users/current", null).status);
        assertEquals(0, auth.refreshes);
    }
    @Test public void officialApiKeyCanOnlyBeUsedForLegacyHeartbeat() throws Exception {
        Auth auth = new Auth(); auth.legacy = true;
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth, (url, method, bytes, header, check) -> {
            assertTrue(header.startsWith("Basic ")); return new ApiRequestRunner.Response(202, "{}");
        });
        failure("reauth_required", runner, OFFICIAL, "", false, null);
        assertEquals(202, runner.execute(OFFICIAL, "", true, "/users/current/heartbeats.bulk", new byte[]{1}).status);
        auth.legacy = false;
        try { runner.execute(OFFICIAL, "", true, "/users/current/heartbeats.bulk", new byte[]{1}); fail(); }
        catch (ApiRequestRunner.Failure error) { assertEquals("link_changed", error.code); }
    }
    @Test public void generationsPreventSwitchAwayAndBackFromReusingQueuedRequest() {
        assertFalse(OFFICIAL.same(new ApiRequestRunner.Target(true, OFFICIAL.apiUrl, OFFICIAL.apiKey, 3)));
        assertFalse(OFFICIAL.same(new ApiRequestRunner.Target(true, OFFICIAL.apiUrl, "changed-key", 1)));
    }
    @Test public void invalidDestinationRejectedBeforeCredentialsOrTransport() throws Exception {
        Auth auth = new Auth();
        ApiRequestRunner runner = new ApiRequestRunner(target -> true, auth,
                (url, method, bytes, header, check) -> { throw new AssertionError("Must not send"); });
        for (String url : new String[]{"https://evil.example/api/v1", "https://wakatime.com@evil.example/api/v1", "http://api.wakatime.com/api/v1"})
            failure("invalid_destination", runner, new ApiRequestRunner.Target(true, url, "key", 1), "link", false, null);
    }
    @Test public void migrationKeepsCustomUrlAndRejectsOfficialHostInCustomMode() {
        assertTrue(ConnectionSettings.defaultOfficial("https://wakatime.com/api/v1"));
        assertTrue(ConnectionSettings.defaultOfficial(""));
        assertFalse(ConnectionSettings.defaultOfficial("https://wakapi.example/api"));
        assertEquals("https://wakapi.example/api", ConnectionSettings.validateCustom("https://wakapi.example/api/"));
        assertNull(ConnectionSettings.validateCustom("https://api.wakatime.com/api/v1"));
        assertNull(ConnectionSettings.validateCustom("https://wakapi.example/api?api_key=secret"));
        assertNull(ConnectionSettings.validateCustom("https://user:secret@wakapi.example/api"));
    }
}
