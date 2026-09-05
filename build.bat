@echo off
REM Build the project using the Gradle wrapper if available, otherwise use Gradle on PATH.
if exist "gradlew.bat" (
    call "gradlew.bat" build --no-daemon
) else (
    where gradle >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo Gradle is not installed and gradlew.bat is missing.
        exit /b 1
    )
    gradle build
)
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    exit /b %ERRORLEVEL%
)
echo Build succeeded.
