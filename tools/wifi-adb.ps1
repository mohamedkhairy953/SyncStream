#requires -Version 5.1
<#
.SYNOPSIS
  Remember lab devices and reconnect them over Wi-Fi ADB.

.DESCRIPTION
  Devices are stored in tools\wifi-adb-devices.json (gitignored).
  Android 11+ "Wireless debugging" changes the connect port on every
  toggle/reboot, so `reconnect` re-learns the port from adb's device list
  and falls back to mDNS discovery.

.EXAMPLES
  .\tools\wifi-adb.ps1 list
  .\tools\wifi-adb.ps1 add -Name realme -Ip 192.168.1.9 -Port 33455
  .\tools\wifi-adb.ps1 pair -Name realme -Ip 192.168.1.9 -Port 44331 -Code 901328
  .\tools\wifi-adb.ps1 reconnect
  .\tools\wifi-adb.ps1 reconnect -Name tablet
#>
param(
    [ValidateSet('list', 'add', 'remove', 'pair', 'connect', 'reconnect')]
    [string]$Command = 'reconnect',
    [string]$Name,
    [string]$Ip,
    [int]$Port,
    [string]$Code
)

$ErrorActionPreference = 'Stop'
$storePath = Join-Path $PSScriptRoot 'wifi-adb-devices.json'

function Get-Adb {
    $candidates = @()
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($root) { $candidates += (Join-Path $root 'platform-tools\adb.exe') }
    }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $candidates = $candidates | Where-Object { Test-Path -LiteralPath $_ }
    if ($candidates) { return @($candidates)[0] }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. Set ANDROID_HOME or add platform-tools to PATH.'
}

$adb = Get-Adb

function Read-Store {
    if (-not (Test-Path -LiteralPath $storePath)) { return @() }
    $raw = Get-Content -LiteralPath $storePath -Raw
    if (-not $raw.Trim()) { return @() }
    $data = ConvertFrom-Json -InputObject $raw
    if ($data -is [System.Array]) { return $data }
    return @($data)
}

function Write-Store($devices) {
    $sorted = @($devices | Sort-Object name)
    $json = ConvertTo-Json -InputObject $sorted -Depth 4
    Set-Content -LiteralPath $storePath -Value $json -Encoding UTF8
}

function Get-OnlineDevices {
    $result = @{}
    foreach ($line in (& $adb devices)) {
        if ($line -match '^(\S+)\s+device\b') {
            $target = $Matches[1]
            if ($target -match '^(\d+\.\d+\.\d+\.\d+):(\d+)$') {
                $result[$Matches[1]] = [int]$Matches[2]
            }
        }
    }
    return $result
}

function Connect-Device($device) {
    $targets = @()
    if ($Port -gt 0) { $targets += $Port }
    if ($device.port) { $targets += [int]$device.port }
    $targets = $targets | Select-Object -Unique

    foreach ($p in $targets) {
        $response = & $adb connect "$($device.ip):$p" 2>&1
        if ($response -match 'connected to') {
            Write-Output "  $($device.name): $($device.ip):$p"
            $device.port = $p
            return
        }
    }
    Write-Warning "  $($device.name) ($($device.ip)) unreachable"
}

$devices = @(Read-Store)
$selected = $devices
if ($Name) {
    $selected = @($devices | Where-Object { $_.name -eq $Name })
    if (-not $selected) { throw "No saved device named '$Name'. Run 'list' first." }
}

switch ($Command) {
    'list' {
        $online = Get-OnlineDevices
        if (-not $devices) { Write-Output 'No saved devices.' }
        foreach ($d in $devices) {
            $state = if ($online.ContainsKey($d.ip)) { 'online' } else { 'offline' }
            Write-Output ("{0,-10} {1,-15} {2,-6} {3}" -f $d.name, $d.ip, $d.port, $state)
        }
    }
    'add' {
        if (-not $Name -or -not $Ip) { throw 'add needs -Name and -Ip.' }
        $devices = @($devices | Where-Object { $_.name -ne $Name })
        $devices += [pscustomobject]@{ name = $Name; ip = $Ip; port = $Port }
        Write-Store $devices
        Write-Output "Saved $Name -> $Ip`:$Port"
    }
    'remove' {
        if (-not $Name) { throw 'remove needs -Name.' }
        Write-Store @($devices | Where-Object { $_.name -ne $Name })
        Write-Output "Removed $Name"
    }
    'pair' {
        if (-not $Ip -or -not $Port -or -not $Code) { throw 'pair needs -Ip, -Port and -Code.' }
        $result = & $adb pair "$Ip`:$Port" $Code 2>&1
        Write-Output $result
        if ($result -match 'Successfully paired') {
            $deviceName = if ($Name) { $Name } else { $Ip }
            $devices = @($devices | Where-Object { $_.name -ne $deviceName })
            $devices += [pscustomobject]@{ name = $deviceName; ip = $Ip; port = 0 }
            Write-Store $devices
            Write-Output "Paired $deviceName ($Ip). Now run 'reconnect -Name $deviceName -Port <connect-port>'."
        }
    }
    { $_ -in 'connect', 'reconnect' } {
        if (-not $selected) { throw 'No devices saved. Use add/pair first.' }
        foreach ($online in (Get-OnlineDevices).GetEnumerator()) {
            foreach ($d in $selected) {
                if ($d.ip -eq $online.Key) { $d.port = $online.Value }
            }
        }
        Write-Output 'Connecting:'
        foreach ($d in $selected) { Connect-Device $d }
        Write-Store $devices
        & $adb devices -l
    }
}

