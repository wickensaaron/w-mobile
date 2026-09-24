# W Media Player mobile authentication and W Core

## Local build configuration

Set these values in the ignored repository-root `local.properties` or matching environment variables:

```properties
NUVIO_SUPABASE_URL=https://mclzacllioaczhrjdehv.supabase.co
NUVIO_SUPABASE_ANON_KEY=<Supabase publishable key>
WCORE_BASE_URL=https://<deployed W Core host>
```

`WCORE_BASE_URL` is optional. It must be a single HTTPS origin with no path, user information, query, or fragment. The generated mobile client pins this origin at build time. If omitted or invalid, Settings → Integrations shows **W Core: Not configured in this build**. The existing local and Supabase features remain available when W Core is down.

W Core must separately be configured with the exact Supabase project origin as `WCORE_SUPABASE_URL` and have its database and Redis ready. The mobile app exchanges the current Supabase access token using `POST /api/v1/auth/supabase`; it renews its in-memory Core token before the 15-minute expiry. The Core token is cleared on sign-out, account change, server switch, and profile switch. No Core token or provider credential is stored by this bridge.

## Supabase authentication settings

Add these exact URLs to the Supabase Auth redirect allow list:

```text
wmedia://auth/google
wmedia://auth/confirm
```

Email registration requests the `/confirm` URL explicitly. Google sign-in requests `/google`. Both are registered as Android and iOS app links and handled by the Supabase Kotlin SDK. A production HTTPS landing page can be used for the general Supabase Site URL; mobile registration does not depend on that fallback. A default `http://localhost:3000` Site URL cannot serve as a released mobile email callback.

To enable Google accounts, create a **Web application** OAuth client in Google Cloud. Its authorized redirect URI must be:

```text
https://mclzacllioaczhrjdehv.supabase.co/auth/v1/callback
```

Enter that client ID and secret only in Supabase Auth → Providers → Google, then enable the provider. The mobile app uses the public publishable key and never embeds the Google client secret. Until this dashboard setup is complete, tapping **Continue with Google** shows a provider-unavailable message and leaves email and guest sign-in available.

## Verification

On Android, run `:composeApp:testAndroidHostTest` for `WCoreConnectionTest`, `GoogleProviderSettingsTest`, `SupabaseAuthDeepLinkTest`, and `LocalHourMinuteDisplayTest`, then `:androidApp:assembleFullDebug`. An end-to-end Google account test requires the external Google OAuth credentials and provider enabled in the live Supabase project. An end-to-end W Core connection test requires a deployed HTTPS Core origin with `/readyz` healthy.
