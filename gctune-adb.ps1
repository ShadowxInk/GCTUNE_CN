# gctune-adb — отправка команды в GC Tune через ADB-мост и печать результата.
#
#   gctune-adb "id"
#   gctune-adb "getprop ro.product.model"
#   gctune-adb "dumpsys deviceidle whitelist" -Wait 40
#
# Требования: в приложении включён «Команды по ADB» (страница «Прочее»),
# GC Tune открыт, устройство подключено по adb.
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Command,

    # сколько секунд ждать ответа канала (канал быстр, но режим игры может
    # потребовать ручного открытия шторки Game Cube)
    [int]$Wait = 30,

    [string]$Uri = 'content://com.poc.gctune.bridge'
)

$ErrorActionPreference = 'Stop'

# 1) отправка: exec возвращает ack (или мгновенную ошибку: disabled/no_game/busy).
#    Команда экранируется для sh на устройстве, иначе пробелы в ней ломают
#    разбор --arg (adb shell склеивает аргументы в одну строку без кавычек).
$escCmd = $Command -replace "'", "'\''"
$escUri = $Uri -replace "'", "'\''"
$sent = adb shell "content call --uri '$escUri' --method exec --arg '$escCmd' --extra wait:i:0" 2>&1 | Out-String

$status = if ($sent -match 'status=(\w+)') { $Matches[1] } else { '?' }
$ack = if ($sent -match 'ack=(-?\d+)') { [long]$Matches[1] } else { -1 }
$msg = if ($sent -match 'msg=([^,}\]]+)') { $Matches[1].Trim() } else { '' }

if ($status -notin @('pending', 'ok')) {
    Write-Output "[$status] $msg"
    if ($sent -match 'out=([\s\S]*?)\s*\}\]\s*$') { Write-Output $Matches[1] }
    exit 1
}
if ($ack -le 0 -and $status -ne 'ok') {
    Write-Output "[$status] $msg"
    exit 1
}

# 2) ожидание: опрашиваем зеркало Settings.Secure.gc_tune_adb_out
#    формат строки: ack|STATUS|rc|base64(utf8)
$deadline = (Get-Date).AddSeconds($Wait)
$raw = $null
do {
    $line = (adb shell settings get secure gc_tune_adb_out) 2>$null
    if ($line) {
        $line = ($line | Out-String).Trim() -replace "`r|`n", ''
        if ($line -and $line -ne 'null') {
            $f = $line.Split('|', 4)
            if ($f.Count -eq 4) {
                $lineAck = 0
                [void][long]::TryParse($f[0], [ref]$lineAck)
                if (($ack -le 0 -or $lineAck -eq $ack) -and $f[1] -ne 'PENDING') {
                    $raw = $f
                    break
                }
            }
        }
    }
    Start-Sleep -Milliseconds 800
} while ((Get-Date) -lt $deadline)

if (-not $raw) {
    Write-Output "[timeout] за $Wait c ответа не было."
    Write-Output "Канал ждёт: режим игры (добавьте GC Tune в список игр и откройте шторку)."
    exit 2
}

# 3) расшифровка
$state = $raw[1]
$rc = $raw[2]
$text = ''
if ($raw[3]) {
    try {
        $bytes = [Convert]::FromBase64String($raw[3])
        $text = [Text.Encoding]::UTF8.GetString($bytes)
    } catch {
        $text = $raw[3]
    }
}

Write-Output "[$state rc=$rc]"
if ($text) { Write-Output $text }
if ($state -ne 'OK') { exit 1 }
