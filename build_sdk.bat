@echo off
echo Starting build...
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo JAVA_HOME set to: %JAVA_HOME%
echo Running gradle from: %~dp0
call "%~dp0gradlew.bat" :veritypro-sdk:assembleDebug --no-daemon
echo Build completed with exit code: %ERRORLEVEL%
