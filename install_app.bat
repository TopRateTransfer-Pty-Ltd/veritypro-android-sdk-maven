@echo off
echo ======================================
echo Installing app to emulator...
echo ======================================
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo JAVA_HOME=%JAVA_HOME%
echo.
echo Running Gradle installDebug...
call "%~dp0gradlew.bat" :app:installDebug --no-daemon --console=plain
echo.
echo ======================================
echo Install completed with exit code: %ERRORLEVEL%
echo ======================================
