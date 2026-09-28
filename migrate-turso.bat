@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0migrate-turso.ps1"
set MIGRATION_EXIT_CODE=%ERRORLEVEL%
echo.
if %MIGRATION_EXIT_CODE% equ 0 (
    echo Turso migration completed successfully.
) else (
    echo Turso migration failed with exit code %MIGRATION_EXIT_CODE%.
)
echo Press any key to close this window.
pause >nul
exit /b %MIGRATION_EXIT_CODE%
