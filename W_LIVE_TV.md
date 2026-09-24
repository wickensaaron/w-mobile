# W Live TV on mobile

The Live TV tab accepts user supplied M3U playlists (URL or local file), Xtream accounts, and Stalker portals. A separate XMLTV URL adds a six-hour, touch-scrollable programme grid. Channel and programme taps open the existing player. Favourites and the last watched channel remain profile scoped.

The provider models, M3U parser, file picker, and portal request flow were selectively adapted from the GPL-3.0 [NuvioMobile-Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced) fork (reference revision `63bced89`). The UI, XMLTV reader, time grid, and credential handling here are W adaptations. The existing NuvioMobile GPL-3.0 licence and notices apply.

Source URLs, imported playlist contents, XMLTV URLs, and portal credentials remain in profile scoped **process memory**. They are cleared when the app process ends and must be entered again on the next launch. This interim behavior avoids writing token-bearing data to ordinary app preferences. The Android implementation clears earlier Live TV preference data on first launch; iOS removes older per-profile source keys. Favourites and last-watched channel IDs are persisted. Secure Core-backed provider management is a separate integration step.

The XMLTV reader supports plain XMLTV programme entries with start/stop, channel ID, title, and optional description. It matches `tvg-id` (or channel name) from M3U and `epg_channel_id` from Xtream. The current grid shows six hours in UTC and refreshes its clock each minute. Compressed `.gz` guides, catch-up, recording, and provider-specific guide discovery are not implemented.

To smoke test in the Android emulator, run [the local fixture](test-fixtures/livetv/README.md). iOS source is included but requires a macOS/Xcode build before it can be called verified.
