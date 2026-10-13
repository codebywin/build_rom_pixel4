@echo off
title Android Remote Relay Server & Ngrok
cd /d "%~dp0"

echo =======================================================
echo   KHOI DONG RELAY SERVER VA NGROK CHO ANDROID REMOTE
echo =======================================================
echo.

:: 1. Khoi dong Relay Server Node.js
start "Relay Server" cmd /k "cd /d "%~dp0server" && node server.js"

:: 2. Khoi dong Ngrok voi Ten Mien Tinh Co Dinh Moi
start "Ngrok Tunnel" cmd /k "npx --yes ngrok http --url=https://gents-underfoot-expend.ngrok-free.dev 3000"

echo [OK] Da kich hoat Server va Ngrok thanh cong!
echo Duong link Web Controller (Chinh - VPS):  http://103.182.19.35:3000
echo Duong link Web Controller (Du phong):     https://gents-underfoot-expend.ngrok-free.dev
echo.
pause
