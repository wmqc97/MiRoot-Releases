param(
    [string]$PackageName = "com.wmqc.miroot",
    [string]$ActivityName = ".MainActivity",
    [int]$DisplayId = 1
)

$ErrorActionPreference = "Stop"

function Step($msg) {
    Write-Host ""
    Write-Host "==> $msg"
}

function Run-Adb([string]$cmd) {
    $full = "adb $cmd"
    Write-Host "PS> $full"
    $out = & adb $cmd.Split(" ")
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $full"
    }
    return ($out -join "`n")
}

function Get-TaskIdFromStackList([string]$stack, [string]$pkg) {
    $pattern = "taskId=(\d+):\s+$([regex]::Escape($pkg))/"
    $m = [regex]::Match($stack, $pattern)
    if (-not $m.Success) {
        return $null
    }
    return [int]$m.Groups[1].Value
}

Step "Checking adb device"
$devices = Run-Adb "devices"
if ($devices -notmatch "device`r?`n") {
    throw "No adb device connected."
}

$component = "$PackageName/$ActivityName"
Step "Starting activity $component"
Run-Adb "shell am start -n $component" | Out-Null
Start-Sleep -Milliseconds 300

Step "Reading taskId from stack list"
$before = Run-Adb "shell am stack list"
$taskId = Get-TaskIdFromStackList -stack $before -pkg $PackageName
if ($null -eq $taskId) {
    throw "Cannot find taskId for package $PackageName in 'am stack list'."
}
Write-Host "Detected taskId=$taskId"

Step "Moving taskId=$taskId to displayId=$DisplayId"
$moveOut = Run-Adb "shell service call activity_task 50 i32 $taskId i32 $DisplayId"
Write-Host $moveOut
Start-Sleep -Milliseconds 200

Step "Verifying task display"
$after = Run-Adb "shell am stack list"
$verifyPattern = "RootTask id=\d+.*displayId=$DisplayId[\s\S]*?taskId=${taskId}:"
if ($after -match $verifyPattern) {
    Write-Host "PASS: taskId=$taskId is on displayId=$DisplayId"
    exit 0
}

Write-Host "FAIL: taskId=$taskId is not confirmed on displayId=$DisplayId"
exit 1
