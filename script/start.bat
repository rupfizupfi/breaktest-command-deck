@echo off
echo Breaktest command deck App is starting...

REM Change to the script directory and move one up
cd %~dp0 && cd ..
echo Current directory: %CD%

REM Start the Java application and redirect output to app.log
start "breaktest-command-deck" /high /B cmd /c "java -jar build/libs/breaktest-command-deck.jar > script/app.log"

REM Wait for a short period to ensure the Java process starts
timeout /t 5 /nobreak > nul

REM Find the PID of the running Java process using wmic and command line
for /f "skip=1 tokens=1" %%i in ('wmic process where "CommandLine like 'java%%' and CommandLine like '%%breaktest-command-deck.jar%%'" get ProcessId') do (
    set "APP_PID=%%i"
    goto :found_pid
)
:found_pid

REM Remove any leading/trailing spaces from APP_PID
set "APP_PID=%APP_PID: =%"

REM Check if APP_PID is set correctly
if "%APP_PID%"=="" (
    echo Failed to find the Java process. Exiting...
    exit /b 1
) else (
    echo Java application PID: %APP_PID%
)

REM Open the application in the default web browser
timeout /t 10 /nobreak > nul
start http://localhost:8080
echo Application started. Java PID: %APP_PID%

REM Wait until the user closes the script window
echo Press any key to stop the application...
pause > nul

REM Attempt to gracefully terminate the Java application
taskkill /PID %APP_PID%

REM If the application hasn't closed, forcefully terminate it
timeout /t 5 /nobreak > nul
tasklist /FI "PID eq %APP_PID%" | find "%APP_PID%" > nul
if not errorlevel 1 (
    echo Forcibly terminating the Java application...
    taskkill /PID %APP_PID% /F
)

echo Application stopped.
@echo on
