@echo off
REM Starter script: builds and launches the Pool Service Customer Manager with Gradle.
where gradle >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Gradle is not installed or not on the PATH.
    echo Install Gradle or use a Gradle wrapper before running this script.
    exit /b 1
)
echo Building the project with Gradle...
gradle build
if %ERRORLEVEL% neq 0 (
    echo Gradle build failed.
    exit /b %ERRORLEVEL%
)
echo Launching the Pool Service Customer Manager...
gradle run
