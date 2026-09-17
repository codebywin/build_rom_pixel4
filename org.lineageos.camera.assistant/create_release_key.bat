@echo off
chcp 65001 >nul
title Tao Release Keystore Cho Camera Assistant

cd /d "%~dp0"

echo ================================================================
echo         CONG CU TAO KEYSTORE RIENG CHO BAN RELEASE
echo ================================================================
echo.

where keytool >nul 2>nul
if %errorlevel% neq 0 (
    echo [LOI] Khong tim thay keytool cua Java! Vui long cai dat JDK.
    pause
    exit /b 1
)

set KEY_FILE=release.keystore
set /p KEY_FILE="Nhap ten file keystore (Mac dinh: release.keystore): "
if "%KEY_FILE%"=="" set KEY_FILE=release.keystore

set KEY_ALIAS=vcam_release
set /p KEY_ALIAS="Nhap Key Alias (Mac dinh: vcam_release): "
if "%KEY_ALIAS%"=="" set KEY_ALIAS=vcam_release

set KEY_PASS=vcam123456
set /p KEY_PASS="Nhap mat khau (Toi thieu 6 ky tu, mac dinh: vcam123456): "
if "%KEY_PASS%"=="" set KEY_PASS=vcam123456

set OWNER_NAME=CameraAssistant
set /p OWNER_NAME="Nhap ten cua ban hoac to chuc (Mac dinh: CameraAssistant): "
if "%OWNER_NAME%"=="" set OWNER_NAME=CameraAssistant

echo.
echo Dang khoi tao %KEY_FILE% ...
keytool -genkeypair -v -keystore "%KEY_FILE%" -alias "%KEY_ALIAS%" -keyalg RSA -keysize 2048 -validity 10000 -storepass "%KEY_PASS%" -keypass "%KEY_PASS%" -dname "CN=%OWNER_NAME%, OU=LineageOS, O=Pixel4, L=VN, ST=VN, C=VN"

if %errorlevel% equ 0 (
    echo.
    echo ================================================================
    echo [OK] DA TAO KEY THANH CONG: %KEY_FILE%
    echo ================================================================
    echo Dang cap nhat thong tin vao keystore.properties ...
    (
        echo # CAU HINH KEYSTORE KÝ BAN RELEASE
        echo keystore_file=%KEY_FILE%
        echo keystore_pass=%KEY_PASS%
        echo key_alias=%KEY_ALIAS%
        echo key_pass=%KEY_PASS%
    ) > keystore.properties
    echo [OK] Da cap nhat keystore.properties tu dong!
    echo Tu gio khi build Release, script se tu dong ky bang key nay.
) else (
    echo.
    echo [LOI] Khong the tao keystore!
)

echo.
pause