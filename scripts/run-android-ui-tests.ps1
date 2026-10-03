# Runs Android UI tests only on the disposable Jianyu_UiTests_API_35 AVD.
# Never use Gradle connectedDebugAndroidTest on an emulator that holds family data:
# Android's connected-test cleanup may uninstall the app and erase its vault.
param(
    [string]$Serial = 'emulator-5556'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$androidRoot = Join-Path $projectRoot 'apps\jianyu-android'
$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $projectRoot '.toolchains\android-sdk' }
$avdRoot = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $projectRoot '.toolchains\avd' }
$jdkRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $projectRoot '.toolchains\jdk17\jdk-17.0.20.1+1' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$emulator = Join-Path $sdkRoot 'emulator\emulator.exe'
$avdName = 'Jianyu_UiTests_API_35'
$avdConfig = Join-Path $avdRoot "$avdName.ini"

foreach ($required in @($adb, $emulator, $avdConfig, (Join-Path $jdkRoot 'bin\java.exe'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "缺少测试工具或专用模拟器：$required。请先按 apps/jianyu-android/README.md 创建测试 AVD。" }
}
if ($Serial -ne 'emulator-5556') { throw '此脚本只允许使用专用测试模拟器 emulator-5556。' }

$env:JAVA_HOME = $jdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_AVD_HOME = $avdRoot
$env:Path = "$env:SystemRoot\System32;$jdkRoot\bin;$sdkRoot\platform-tools;$env:Path"

$avdIdentity = (& $adb -s $Serial emu avd name 2>$null | Out-String).Trim()
if ($avdIdentity -notmatch [regex]::Escape($avdName)) {
    if ($avdIdentity -and $avdIdentity -notmatch 'offline|not found|no devices') {
        throw "端口 5556 已由其他模拟器占用：$avdIdentity"
    }
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $avdName, '-port', '5556', '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect') -WindowStyle Hidden | Out-Null
}

$booted = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    $bootState = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($bootState -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $booted) { throw '专用测试模拟器未能在两分钟内启动。' }
$avdIdentity = (& $adb -s $Serial emu avd name | Out-String).Trim()
if ($avdIdentity -notmatch [regex]::Escape($avdName)) { throw "模拟器身份不匹配：$avdIdentity" }

Push-Location $androidRoot
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'Android 测试包编译失败。' }
    $appApk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $androidRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    foreach ($apk in @($appApk, $testApk)) {
        & $adb -s $Serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "无法安装测试包：$apk" }
    }
    $result = (& $adb -s $Serial shell am instrument -w -r 'org.jianyu.app.test/androidx.test.runner.AndroidJUnitRunner' | Out-String)
    Write-Output $result
    if ($LASTEXITCODE -ne 0 -or $result -notmatch 'OK \(\d+ tests?\)' -or $result -match 'FAILURES') {
        throw 'Android 界面测试未全部通过。'
    }
} finally {
    Pop-Location
}
