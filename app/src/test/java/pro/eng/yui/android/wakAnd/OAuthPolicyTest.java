package pro.eng.yui.android.wakAnd;
import org.junit.Test;
import static org.junit.Assert.*;

public class OAuthPolicyTest {
    @Test public void fixedOriginsRejectCredentialRedirection() {
        assertTrue(OAuthPolicy.officialApi("https://api.wakatime.com/api/v1"));
        assertTrue(OAuthPolicy.officialApi("https://wakatime.com/api/v1/"));
        for (String target : new String[]{"http://wakatime.com/api/v1", "https://wakatime.com.evil/api/v1", "https://user@wakatime.com/api/v1", "https://wakatime.com:444/api/v1", "https://wakatime.com/api/v1?x=1", "https://wakatime.com/api/v1/../other", "https://wakapi.example/api/v1"})
            assertFalse(target, OAuthPolicy.officialApi(target));
    }
    @Test public void callbackMustMatchPendingLinkExactly() {
        String id = "AAAAAAAAAAAAAAAAAAAAAA", url = OAuthPolicy.AUTH_BASE + "app/complete?link_id=" + id;
        assertTrue(OAuthPolicy.completion(url, id));
        assertFalse(OAuthPolicy.completion(url + "&access_token=secret", id));
        assertFalse(OAuthPolicy.completion(url + "#secret", id));
        assertFalse(OAuthPolicy.completion(url.replace("app/complete", "oauth/callback"), id));
        assertFalse(OAuthPolicy.completion(url.replace(id, "BBBBBBBBBBBBBBBBBBBBBA"), id));
    }
    @Test public void startBrowserLinkIsRestrictedToLaunch() throws Exception {
        String id = "AAAAAAAAAAAAAAAAAAAAAA", launch = OAuthPolicy.randomSecret();
        String url = OAuthPolicy.AUTH_BASE + "oauth/launch?link_id=" + id + "&launch_token=" + launch;
        assertTrue(OAuthPolicy.launch(url, id));
        assertFalse(OAuthPolicy.launch(url.replace("yui.eng.pro", "example.com"), id));
        assertFalse(OAuthPolicy.launch(url + "&return_url=https://evil", id));
        assertEquals(32, OAuthPolicy.decode(launch, 32).length);
        assertEquals(32, OAuthPolicy.decode(OAuthPolicy.hash(launch, false), 32).length);
    }
}
