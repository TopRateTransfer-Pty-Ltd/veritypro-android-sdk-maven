$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location "c:\Users\oia89\OneDrive\Pictures\Veritypro-android-sdk-development\Veritypro-android-sdk-development"
Write-Host "JAVA_HOME: $env:JAVA_HOME"
Write-Host "Starting Gradle build..."
& .\gradlew.bat :app:assembleDebug --no-daemon --console=plain
Write-Host "Build exit code: $LASTEXITCODE"
if ($LASTEXITCODE -eq 0) {
    Write-Host "Installing APK..."
    & "C:/Users/oia89/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r "app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"
    Write-Host "Install exit code: $LASTEXITCODE"
}
