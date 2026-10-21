<#
.SYNOPSIS
    adbplus - execute shell commands on the device through the GC-Tune daemon channel.

.DESCRIPTION
    Full round trip, no root required:
      1. builds the exact body the GC-Tune app builds (base64 output + __RC__ marker
         parked in Settings.System gc_tune_ack as "N|base64", plus the ACK broadcast);
      2. publishes it to Settings.Secure gc_tune_cmd via adb (shell uid has
         WRITE_SECURE_SETTINGS by default);
      3. wakes the uid1000 daemon with a log line: log -t GCTune WAKE <ack>;
      4. polls Settings.System gc_tune_ack until the ack number matches ours;
      5. decodes the base64 payload, strips the __RC__ trailer, prints stdout+stderr,
         and exits with the remote command's exit code.

    The daemon must be running (start the GC-Tune app once, or its DAEMON_BOOT has
    already spawned it). Requires only adb on PATH and a connected device.

.EXAMPLE
    ./adbplus.ps1 id
    ./adbplus.ps1 getprop ro.product.model
    ./adbplus.ps1 -Timeout 30 "ls -la /data/system"
    ./adbplus.ps1 -Raw "echo 'a b' \"c\""

.NOTES
    Exit codes: remote command rc (usually 0..255), 124 = timeout (no daemon/no ack),
    1 = usage or transport error.
#>
[CmdletBinding()]
param(
    # Remote command line. Everything after the options, may be quoted as one arg
    # or several words.
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Command,

    # Poll timeout in seconds (-Timeout 30).
    [Alias('t')]
    [ValidateRange(1, 600)]
    [int]$TimeoutSec = 20,

    # Print raw decoded blob without __RC__ parsing.
    [switch]$Raw,

    # Print total elapsed time to stderr (Verbose).
    [switch]$Measure,

    [switch]$h
)

$ErrorActionPreference = 'Stop'

if ($Measure) { $sw = [Diagnostics.Stopwatch]::StartNew() }

if ($h -or -not $Command -or $Command.Count -eq 0) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 1
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    # adb progress goes to stderr; under PS 5.1 with $ErrorActionPreference='Stop'
    # that surfaces as a terminating NativeCommandError. Wrapping in cmd /c and
    # folding stderr back in keeps it a normal non-terminating message.
    cmd /c "`"adb`" $($AdbArgs -join ' ') 2>&1"
}

# Push $Content to $Remote via adb shell stdin: no quoting/escaping at all, and
# the device-side $(cat file) expands exactly once.
function Push-DeviceFile {
    param([string]$Content, [string]$Remote)
    $tmp = Join-Path $env:TEMP ('gplus_' + $PID + '_' + (Get-Random))
    [IO.File]::WriteAllText($tmp, $Content, (New-Object Text.UTF8Encoding($false)))
    try {
        $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($tmp))
        Invoke-Adb @('shell', "`"echo $b64 | base64 -d > $Remote`"") | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "push to $Remote failed" }
    }
    finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
}

$state = (Invoke-Adb @('get-state') | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $state -notmatch 'device') {
    Write-Error "no device connected (adb get-state: $state)"
    exit 1
}

$remoteCmd = ($Command -join ' ')

# Unique ack number outside the app's 1_000_001+ counter range.
$ack = 1500000000 + (Get-Random -Maximum 99999999)

# Exact body shape produced by Main.apply(): output + __RC__ marker, base64,
# parked in gc_tune_ack as "<ack>|<b64>", broadcast as a side channel.
$body = 'b=$({ ' + $remoteCmd + '; echo __RC__$?; } 2>&1 | base64 -w 0 | head -c 8000);'
$body += 'b=${b:-Cg==};'
$body += 'se=$( { settings put system gc_tune_ack ' + $ack + '\|$b 2>&1; echo RC$?; } | base64 -w 0);'
$body += 'se=${se:-Cg==};'
$body += 'am broadcast -a com.poc.gctune.ACK --ei n ' + $ack + ' --es o $b --es e $se >/dev/null 2>&1'

# Publish via a pushed file - zero shell quoting risk for the payload.
Push-DeviceFile -Content $body -Remote '/data/local/tmp/.gplus_body'

# "$(cat file)" on the device expands once - content is never re-parsed.
Invoke-Adb @('shell', '"settings put secure gc_tune_cmd \"$(cat /data/local/tmp/.gplus_body)\""') | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Error 'settings put failed (WRITE_SECURE_SETTINGS missing?)'; exit 1 }

$check = (Invoke-Adb @('shell', '"settings get secure gc_tune_cmd"') | Out-String).Trim()
if ($check -ne $body) {
    $short = $check.Substring(0, [Math]::Min(80, $check.Length))
    Write-Error "gc_tune_cmd not stored verbatim (got: $short...)"
    exit 1
}

# Wake the daemon. logcat -s GCTune:I inside the daemon matches any *WAKE* line.
Invoke-Adb @('shell', "`"log -t GCTune WAKE $ack`"") | Out-Null

# Poll the parked result.
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$val = $null
while ((Get-Date) -lt $deadline) {
    $v = (Invoke-Adb @('shell', '"settings get system gc_tune_ack"') | Out-String).Trim()
    if ($v -match "^$ack\|(\S+)$") { $val = $Matches[1]; break }
    Start-Sleep -Milliseconds 200
}
if ($null -eq $val) {
    Write-Error "timeout ${TimeoutSec}s: no ack $ack - is the gctw daemon running? (open GC-Tune app once)"
    exit 124
}

$blob = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($val))

if ($Raw) {
    Write-Output $blob
    exit 0
}

$rc = 0
if ($blob -match '__RC__(-?\d+)\s*$') {
    $rc = [int]$Matches[1]
    $blob = $blob -replace '__RC__-?\d+\s*$', ''
}
if ($blob.Length -gt 0) { Write-Output $blob.TrimEnd("`r", "`n") }

Invoke-Adb @('shell', '"rm -f /data/local/tmp/.gplus_body"') | Out-Null
if ($Measure) { $el = [int]$sw.ElapsedMilliseconds; Write-Verbose "adbplus: total ${el}ms (wake-to-ack poll included)" }
exit $rc
