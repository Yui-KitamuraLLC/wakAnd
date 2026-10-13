# wakAnd v1.5.0 release notes

Version code: 22. The publication date will be recorded after release.

## Changes

- Connect, reconnect and disconnect the official WakaTime service using OAuth in an external browser.
- Use OAuth for user information, projects, automatic activity tracking and manual time entries.
- Switch between WakaTime and a custom host. Custom hosts continue using their saved HTTPS API URL and API key.
- Distinguish authentication failures, missing permissions, connection failures and empty project lists. Connection failures retain authorization and app settings.
- Update English/Japanese connection messages and the privacy policy for the authorization backend.

## Upgrading

1. Install the signed APK over the existing app. Do not uninstall or clear app data.
2. For the official service, select WakaTime and open WakaTime OAuth. Authorize in the browser, then check the connection after returning to the app.
3. Existing app tracking settings, project assignments and custom URL/API key settings are retained. Switching destinations does not disconnect OAuth.
4. Migrate to OAuth ahead of the WakaTime authentication restrictions announced for November 1, 2026. Before migration, an existing official API key continues sending automatic activity only. User information, projects and manual entries require OAuth.
5. Expired or disconnected OAuth does not fall back to an API key. Offline disconnect stops local OAuth use and queues provider revocation for a later connection.

## Requirements and limitations

- Android 10 (API 29) or later. Enable Usage Access for app tracking and Accessibility for browser URL tracking.
- Official WakaTime requires an account and OAuth authorization. Custom hosts require their own API key.
- Heartbeats recorded while offline are not queued for later delivery. New activity is sent after connectivity returns.
- If delivery of a manual entry is uncertain, retry with the same screen, destination and fields. Check the dashboard before creating a new entry.
- Custom hosts retain API key authentication, with credential separation covered by automated tests. Compatibility with a real custom server remains unverified.

See [release verification](verification-1.5.0.md) for the candidate status. Preparation and testing do not mean the release has been published.
