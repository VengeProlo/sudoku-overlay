@echo off
setlocal
set VERSION=8.9
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DEST=%GRADLE_USER_HOME%\wrapper\dists\gradle-%VERSION%-bin\sudoku-overlay
set GRADLE=%DEST%\gradle-%VERSION%\bin\gradle.bat
if not exist "%GRADLE%" (
  if not exist "%DEST%" mkdir "%DEST%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%DEST%\gradle-%VERSION%-bin.zip'; Expand-Archive -Path '%DEST%\gradle-%VERSION%-bin.zip' -DestinationPath '%DEST%' -Force"
)
call "%GRADLE%" %*
endlocal
