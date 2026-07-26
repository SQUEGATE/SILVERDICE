@echo off
REM Run the Pool Service Customer Manager using Gradle.
where gradle >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Gradle is not installed or not on the PATH.
    echo Install Gradle or use a Gradle wrapper before running this script.
    exit /b 1
)
gradle run
