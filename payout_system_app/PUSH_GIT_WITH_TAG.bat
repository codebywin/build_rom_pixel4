@echo off
chcp 65001 >nul
title Git Push & Tag Manager - Android Remote Controller + Vcam

:: Cau hinh Repository
set "REPO_URL=https://github.com/codebywin/AndroidRemoteController-Vcam.git"
set "REPO_DISPLAY=https://github.com/codebywin/AndroidRemoteController-Vcam.git"
set "REPO_NAME=codebywin/AndroidRemoteController-Vcam"
set "BRANCH=main"

echo ================================================================
echo        TOOL PUSH CODE VA TAO TAG GITHUB TU DONG
echo  Repository: %REPO_DISPLAY%
echo ================================================================
echo.

:: 1. Kiem tra va tu dong git init + add remote neu chua co
if not exist ".git" (
    echo [*] Thu muc chua co Git. Dang khoi tao Git Repository moi (git init)...
    git init
    git branch -M %BRANCH%
    git remote add origin %REPO_URL%
    echo [V] Da khoi tao Git va ket noi Remote thanh cong!
) else (
    echo [*] Git Repository da ton tai. Kiem tra va cap nhat Remote Origin...
    git remote set-url origin %REPO_URL% >nul 2>&1
    if errorlevel 1 git remote add origin %REPO_URL% >nul 2>&1
)

:: Cau hinh user Git
git config user.name "codebywin" >nul 2>&1
git config user.email "hothangtech@gmail.com" >nul 2>&1

:: Toi uu bo nho Git khi push
git config pack.windowMemory "256m" >nul 2>&1
git config pack.packSizeLimit "256m" >nul 2>&1
git config pack.threads "1" >nul 2>&1
git config core.packedGitLimit "512m" >nul 2>&1
git config core.packedGitWindowSize "512m" >nul 2>&1
git config http.postBuffer 524288000 >nul 2>&1

:: Dam bao .gitignore co ban ton tai de khong push file rac/nang
if not exist ".gitignore" (
    echo [*] Dang tao file .gitignore co ban...
    (
        echo # Gradle ^& Build caches
        echo .gradle/
        echo .kotlin/
        echo build/
        echo app/build/
        echo */build/
        echo # IDE
        echo .idea/
        echo *.iml
        echo .vscode/
        echo # Config ^& Logs
        echo local.properties
        echo *.log
    ) > .gitignore
)

:: 2. Dong bo tag tu remote ve (neu co)
echo [*] Dang kiem tra cac Tag tren Git...
git fetch origin --tags >nul 2>&1

:: 3. Tu dong tinh toan Tag moi va Commit Message theo thoi gian
set "CURRENT_TAG=Chua co"
set "AUTO_TAG=v1.0.0"
set "DEFAULT_MSG=Cap nhat code moi"

if exist "%~dp0scripts\get_git_tag.ps1" (
    for /f "tokens=1,2,* delims=|" %%a in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\get_git_tag.ps1"') do (
        set "CURRENT_TAG=%%a"
        set "AUTO_TAG=%%b"
        set "DEFAULT_MSG=%%c"
    )
) else (
    for /f "tokens=*" %%d in ('powershell -NoProfile -Command "Get-Date -Format 'yyyy-MM-dd HH:mm:ss'"') do set "DEFAULT_MSG=Cap nhat code [%%d]"
    for /f "tokens=*" %%t in ('powershell -NoProfile -Command "Get-Date -Format 'yyyy.MM.dd_HHmm'"') do set "AUTO_TAG=v%%t"
)

echo.
echo ================================================================
echo [1] THONG TIN COMMIT ^& TAG TU DONG:
echo ----------------------------------------------------------------
echo   * Tag gan nhat tren Git : %CURRENT_TAG%
echo   * Tag moi tu dong tang  : %AUTO_TAG%
echo   * Commit goi y theo gio : %DEFAULT_MSG%
echo ----------------------------------------------------------------
echo.

set /p "USER_MSG=1. Nhap noi dung Commit (Enter de lay mac dinh theo gio): "
if "%USER_MSG%"=="" (
    set "MSG=%DEFAULT_MSG%"
) else (
    set "MSG=%USER_MSG%"
)

echo.
set /p "USER_TAG=2. Nhap ten Tag (Enter de lay tu dong tang %AUTO_TAG%): "
if "%USER_TAG%"=="" (
    set "TAG=%AUTO_TAG%"
) else (
    set "TAG=%USER_TAG%"
)

echo.
echo ================================================================
echo  THONG TIN PUSH:
echo   - Noi dung : %MSG%
echo   - Tag      : %TAG%
echo   - Branch   : %BRANCH%
echo ================================================================
echo.

:: 4. Thuc hien Git Add, Commit, Tag va Push
echo [*] Dang kiem tra va them cac file vao Git...
git add -A

echo [*] Dang tao Commit...
git commit -m "%MSG%"
if errorlevel 1 (
    echo [i] Khong co file thay doi moi. Tiep tuc xu ly Tag va Push...
)

echo [*] Dang tao Tag: %TAG%...
git tag -a "%TAG%" -m "%MSG%"
if errorlevel 1 (
    echo [!] Tag %TAG% da ton tai, dang ghi de Tag...
    git tag -fa "%TAG%" -m "%MSG%"
)

echo [*] Dang day Code len Github branch %BRANCH%...
git push origin %BRANCH%
if errorlevel 1 (
    echo [!] Push chua thanh cong, dang thu pull tu remote ve roi day lai...
    git pull origin %BRANCH% --allow-unrelated-histories --no-rebase -q >nul 2>&1
    git push origin %BRANCH%
)

echo [*] Dang day Tag %TAG% len Github...
git push origin "%TAG%" --force

echo.
echo ================================================================
echo [V] HOAN TAT PUSH CODE VA TAO TAG THANH CONG!
echo.
echo - Link xem Tag ^& Release tren Github:
echo   https://github.com/%REPO_NAME%/releases/tag/%TAG%
echo.
echo - Link Repository:
echo   https://github.com/%REPO_NAME%
echo ================================================================
echo.
pause
