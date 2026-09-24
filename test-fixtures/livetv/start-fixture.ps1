$fixtureRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$now = [DateTimeOffset]::UtcNow
$firstStart = $now.AddMinutes(-20).ToString('yyyyMMddHHmmss +0000')
$firstStop = $now.AddMinutes(20).ToString('yyyyMMddHHmmss +0000')
$secondStart = $now.AddMinutes(20).ToString('yyyyMMddHHmmss +0000')
$secondStop = $now.AddMinutes(80).ToString('yyyyMMddHHmmss +0000')
$thirdStart = $now.AddMinutes(80).ToString('yyyyMMddHHmmss +0000')
$thirdStop = $now.AddMinutes(140).ToString('yyyyMMddHHmmss +0000')

@"
<tv>
  <programme start="$firstStart" stop="$firstStop" channel="w.test"><title>W test: on now</title></programme>
  <programme start="$secondStart" stop="$secondStop" channel="w.test"><title>W test: next</title></programme>
  <programme start="$thirdStart" stop="$thirdStop" channel="w.test"><title>W test: later</title></programme>
</tv>
"@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'guide.xml') -Encoding utf8

@"
#EXTM3U
#EXTINF:-1 tvg-id="w.test" group-title="Test",W Test Channel
http://10.0.2.2:8765/sample.mp4
"@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'channels.m3u') -Encoding utf8

python -m http.server 8765 --bind 127.0.0.1 --directory $fixtureRoot
