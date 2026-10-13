package pro.eng.yui.android.wakAnd;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** A request keeps its original destination, OAuth link and POST bytes across a single 401 retry. */
final class ApiRequestRunner {
    static final class Target {
        final boolean official;
        final String apiUrl, apiKey;
        final long generation;
        Target(boolean official, String apiUrl, String apiKey, long generation) {
            this.official = official; this.apiUrl = apiUrl; this.apiKey = apiKey; this.generation = generation;
        }
        boolean same(Target other) {
            return other != null && official == other.official && generation == other.generation
                    && Objects.equals(apiUrl, other.apiUrl) && Objects.equals(apiKey, other.apiKey);
        }
    }
    static final class Credential {
        final String token, linkId;
        final long generation;
        final boolean refreshed;
        Credential(String token, String linkId, long generation, boolean refreshed) {
            this.token = token; this.linkId = linkId; this.generation = generation; this.refreshed = refreshed;
        }
    }
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
    interface Authorization {
        Credential prepare(String url, String linkId, boolean unauthorized, long generation) throws Failure;
        boolean current(Credential credential);
        void rejected(Credential credential) throws Failure;
        boolean legacyAllowed();
    }
    interface Guard { boolean current(Target target); }
    interface Check { void run() throws Failure; }
    interface Transport {
        Response send(String url, String method, byte[] payload, String authorization, Check beforeSend) throws Exception;
    }
    private final Guard guard;
    private final Authorization oauth;
    private final Transport transport;
    ApiRequestRunner(Guard guard, Authorization oauth, Transport transport) {
        this.guard = guard; this.oauth = oauth; this.transport = transport;
    }
    Response execute(Target target, String linkId, boolean legacyHeartbeat, String path, byte[] payload) throws Failure {
        if (!guard.current(target)) throw new Failure("destination_changed");
        if (legacyHeartbeat && !"/users/current/heartbeats.bulk".equals(path)) throw new Failure("reauth_required");
        if (target.official ? !OAuthPolicy.officialApi(target.apiUrl) : ConnectionSettings.validateCustom(target.apiUrl) == null)
            throw new Failure("invalid_destination");
        Credential credential = null;
        boolean bearer = target.official && linkId != null && !linkId.isEmpty();
        if (bearer) credential = oauth.prepare(target.apiUrl, linkId, false, 0);
        else if (target.official && !legacyHeartbeat) throw new Failure("reauth_required");
        else if (target.apiKey == null || target.apiKey.isEmpty()) throw new Failure("no_api_key");

        for (int attempt = 0; attempt < 2; attempt++) {
            final Credential sent = credential;
            Check check = () -> {
                if (!guard.current(target)) throw new Failure("destination_changed");
                if (sent != null && !oauth.current(sent)) throw new Failure("link_changed");
                if (target.official && sent == null && !oauth.legacyAllowed()) throw new Failure("link_changed");
            };
            Response response;
            String authorization = bearer ? "Bearer " + credential.token : "Basic "
                    + Base64.getEncoder().encodeToString(target.apiKey.getBytes(StandardCharsets.UTF_8));
            try {
                check.run();
                response = transport.send(target.apiUrl.replaceAll("/+$", "") + path,
                        payload == null ? "GET" : "POST", payload, authorization, check);
            } catch (Failure error) { throw error; }
            catch (Exception error) {
                if (!guard.current(target)) throw new Failure("destination_changed");
                throw new Failure(payload == null ? "network_unavailable" : "delivery_unknown");
            }
            // A stale response must never populate the UI of the newly selected destination.
            if (!guard.current(target)) throw new Failure("destination_changed");
            if (sent != null && !oauth.current(sent)) throw new Failure("link_changed");
            if (response.status != 401 || !bearer) return response;
            if (attempt == 1 || credential.refreshed) {
                oauth.rejected(credential); return response;
            }
            credential = oauth.prepare(target.apiUrl, linkId, true, credential.generation);
        }
        throw new Failure("unauthorized");
    }
}
