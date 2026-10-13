package pro.eng.yui.android.wakAnd;

final class ApiMessages {
    private ApiMessages() {}
    static int error(String code) {
        if (code == null) return R.string.api_empty_projects;
        switch (code) {
            case "unauthorized": case "reauth_required": case "device_unauthorized":
            case "link_expired": case "link_disconnected": return R.string.api_reauth;
            case "no_api_key": return R.string.offline_no_api_key;
            case "forbidden": return R.string.api_forbidden;
            case "destination_changed": case "link_changed": return R.string.api_destination_changed;
            case "delivery_unknown": return R.string.api_delivery_unknown;
            case "rate_limited": case "operation_in_progress": return R.string.oauth_retry_later;
            case "invalid_destination": return R.string.api_invalid_destination;
            default: return R.string.api_unavailable;
        }
    }
}
