# Local Live TV smoke fixture

Run \`.\start-fixture.ps1\` in PowerShell and leave the server running. It creates a fresh XMLTV guide and M3U playlist alongside the six-second test video.

In the Android emulator, open the Live TV tab, add M3U URL \`http://10.0.2.2:8765/channels.m3u\`, then save XMLTV URL \`http://10.0.2.2:8765/guide.xml\`. The channel should appear with now/next entries and three aligned blocks in Guide. Tap the channel or a block to test playback. This media is a short playback fixture, not a continuous live stream.

The fixture server is bound to the host loopback interface; \`10.0.2.2\` is the Android emulator's host alias. Regenerate the guide by restarting the script if its programmes expire.
