# Samples the Windows host once a second until $StopFile appears.
#
# Why this exists: Docker Desktop's VM is a Windows process (vmmem), and Windows
# pages it out like any other when the host is short of RAM. The guest cannot see
# that - no swap, no memory events, no OOM - it simply stalls, and a JVM inside it
# reports a 15-25 s "minor GC" while its heap is read back from the pagefile.
# P1 at 100 rps on a 7.9 GB laptop ran at 1,300-1,900 hard page-ins per second
# and failed 1,249 requests; the identical run at 60 rps was valid. Nothing inside
# the stack could have told those apart. This can.
param(
  [Parameter(Mandatory = $true)][string]$Out,
  [Parameter(Mandatory = $true)][string]$StopFile
)
$counters = @('\Memory\Pages Input/sec', '\Memory\Available MBytes')
"pagesInPerSec,availableMB" | Set-Content -Encoding ascii $Out
while (-not (Test-Path $StopFile)) {
  try {
    $s = (Get-Counter -Counter $counters -ErrorAction Stop).CounterSamples
    $pin = [int]($s | Where-Object { $_.Path -like '*pages input/sec' }).CookedValue
    $avail = [int]($s | Where-Object { $_.Path -like '*available mbytes' }).CookedValue
    "$pin,$avail" | Add-Content -Encoding ascii $Out
  } catch {
    Start-Sleep -Seconds 1
  }
}
