package pro.eng.yui.android.wakAnd;

import org.json.JSONArray;
import org.json.JSONObject;

final class ApiResponsePolicy {
    private ApiResponsePolicy() {}
    static int heartbeatStatus(String body) throws ApiRequestRunner.Failure {
        try {
            JSONArray responses = new JSONObject(body).getJSONArray("responses");
            if (responses.length() != 1) throw new ApiRequestRunner.Failure("invalid_response");
            JSONArray response = responses.getJSONArray(0);
            if (response.length() != 2 || !(response.get(1) instanceof Number))
                throw new ApiRequestRunner.Failure("invalid_response");
            int status = response.getInt(1);
            if (status < 200 || status > 599) throw new ApiRequestRunner.Failure("invalid_response");
            return status;
        } catch (ApiRequestRunner.Failure error) { throw error; }
        catch (Exception error) { throw new ApiRequestRunner.Failure("invalid_response"); }
    }
}
