@echo off
chcp 65001 >nul 2>&1
echo ========================================
echo   EunSearch Bot Launcher
echo ========================================
echo.

where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js not found. Please install Node.js
    echo          Download: https://nodejs.org/
    pause
    exit /b 1
)
echo [OK] Node.js installed

if not exist "node_modules\" (
    echo [WARN] Dependencies not installed, running npm install...
    call npm install
    if errorlevel 1 (
        echo [ERROR] npm install failed
        pause
        exit /b 1
    )
    echo [OK] Dependencies installed
) else (
    echo [OK] Dependencies ready
)

echo.
echo Starting watchdog...
echo ========================================
node watchdog_direct.js
pause
