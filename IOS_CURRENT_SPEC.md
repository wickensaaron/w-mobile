# iPhone current-spec test build

Prepared 6 October 2026. Target: 0.5.1 (134), full Debug SideStore IPA.

## Included

- Personal For You discovery with up to 24 picks, profile-safe hide/undo, mobile poster sizing, readable colours and no Related to captions.
- Streaming-service lists, film-franchise discovery/search and detail navigation, adapted for phone widths and larger text.
- Responsive profile picker, accessible scrollable PIN entry, serialized account-checked profile transitions.
- Source-owned Xtream catch-up history, start-over, resume and Return to live; provider timezone and retention are validated.
- W Core recording creation/scheduling/series rules, management, library browsing, fresh-link playback/resume and programme-start skip.
- Existing balanced AUTO/English-audio and native download work, plus a corrected Swift backup-exclusion API call.

## Ownership and availability

Recording storage belongs to the Core-selected profile; it is not automatically split by the mobile profile index. Local account/profile/Core-session changes invalidate operations and playback launches. Recording and catch-up URLs are not saved into progress or sent through generic addon metadata/source lookup. Recording requires a compatible configured W Core instance and resident source. Catch-up requires provider-advertised retention, valid provider timezone and eligible programme history.

## Release verification

Shared-source compilation and targeted host tests are run on Windows before the source is frozen. This does not validate native Swift/Kotlin linking or actual iPhone rendering. The Codemagic `sidestore_ipa` workflow is the existing Debug, mac_mini_m2, Xcode 26.6, Java 17 workflow. Use only the frozen revision and avoid parallel/duplicate cloud runs.

Before calling this release-ready, install the produced IPA and verify: sign-in and profile isolation; 320/375/390/430-point layouts and large text; safe areas/keyboard/VoiceOver; discovery actions; service/collection navigation; live and replay playback including Return to live; current/future recording, stop/extend/delete, fresh-link resume and pre-roll skip; background downloads; AUTO English audio; and device temperature. No production recordings were created or deleted during local development.
