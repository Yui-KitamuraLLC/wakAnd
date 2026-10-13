package pro.eng.yui.android.wakAnd;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiResponsePolicyTest {
    @Test public void bulkUsesTheIndividualStatusIncludingAuthAndPermissionFailures() throws Exception {
        for (int status : new int[]{201, 202, 400, 401, 403, 429, 500})
            assertEquals(status, ApiResponsePolicy.heartbeatStatus("{\"responses\":[[{\"data\":{}}," + status + "]] }"));
    }
    @Test public void malformedOrMissingResultsCannotBeReportedAsSuccess() throws Exception {
        for (String body : new String[]{"{}", "{\"responses\":[]}", "{\"responses\":[[{},201],[{},403]]}",
                "{\"responses\":[[{}]]}", "{\"responses\":[[{},\"201\"]]}", "{\"responses\":[[{},0]]}"}) {
            try { ApiResponsePolicy.heartbeatStatus(body); fail(); }
            catch (ApiRequestRunner.Failure error) { assertEquals("invalid_response", error.code); }
        }
    }
}
