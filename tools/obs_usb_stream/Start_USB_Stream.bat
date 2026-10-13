@echo off
chcp 65001 >nul
title OBS Studio to VCam - USB Stream Tool
color 0B

echo ======================================================================
echo           BO CONG CU STREAM OBS STUDIO SANG VCAM QUA CAP USB
echo                        codebywin - @zero0x0134w
echo ======================================================================
echo.

:: Kiem tra Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [X] LOI: Khong tim thay Python tren may tinh!
    echo     Vui long cai dat Python 3.8+ tu python.org va tich chon "Add Python to PATH".
    echo.
    pause
    exit /b 1
)

:: Kiem tra FFmpeg (Neu chua co thi tu dong tai ve)
set "LOCAL_FFMPEG=%~dp0ffmpeg.exe"
where ffmpeg >nul 2>&1
if errorlevel 1 (
    if not exist "%LOCAL_FFMPEG%" (
        echo ======================================================================
        echo  [*] CHUA CO FFMPEG TREN MAY TINH!
        echo  [*] DANG TU DONG TAI FFMPEG VE THU MUC TOOL...
        echo ======================================================================
        echo.
        python "%~dp0download_ffmpeg.py"
        if errorlevel 1 (
            echo.
            echo [X] LOI: Khong the tu dong tai FFmpeg!
            echo     Vui long kiem tra ket noi Internet va thu lai.
            echo.
            pause
            exit /b 1
        )
        echo.
    )
)

:: Them thu muc hien tai va Android SDK platform-tools vao PATH
set "PATH=%~dp0;%PATH%"
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools;%PATH%"
)

:: Chay cau noi OBS Bridge
python "%~dp0obs_bridge.py"

if errorlevel 1 (
    echo.
    echo Da xay ra loi trong qua trinh chay.
    pause
)
