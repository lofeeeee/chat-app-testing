@echo off
REM ===========================================================================
REM  Opens one more client window against the server that's already running.
REM
REM  For testing two accounts side by side: sign this one in as the other user
REM  and watch messages and typing indicators cross between the windows.
REM
REM  The window is DISPOSABLE. It runs against a fresh temporary data
REM  directory, so it:
REM
REM    * starts signed out, ignoring whatever session the main window saved,
REM    * cannot overwrite that session when you sign in here,
REM    * gets its own device id, so the two windows show up as two separate
REM      entries in "where you're signed in" rather than fighting over one.
REM
REM  Isolating the whole directory is what makes all three true at once. A
REM  "don't save my login" flag would still have shared the device id.
REM
REM  It launches from an exported classpath rather than `gradlew run`, because
REM  `run` holds the Gradle build open for the app's whole lifetime and a
REM  second invocation would block forever on the project lock.
REM ===========================================================================

setlocal
call "%~dp0_env.bat" || exit /b 1

set "ARGFILE=%PROJECT_ROOT%\client\composeApp\build\singular-args.txt"
if not exist "%ARGFILE%" (
  echo   No launch argfile yet. Run build.bat first.
  exit /b 1
)

curl -s -o nul -f http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 (
  echo   The server isn't up. Run start.bat first.
  exit /b 1
)

REM %RANDOM% so several throwaway windows can be open at once without sharing
REM a directory — otherwise the second would inherit the first one's session,
REM which is the exact problem this is here to avoid.
set "TEMPDATA=%TEMP%\singular-temp-%RANDOM%%RANDOM%"
mkdir "%TEMPDATA%" 2>nul

echo   Opening a temporary Singular window...
echo   Data directory: %TEMPDATA%
start "Singular client (temporary)" cmd /k ""%JAVA_EXE%" "-Dsingular.dataDir=%TEMPDATA%" "@%ARGFILE%""

echo.
echo     nova@singular.test  /  singular-demo
echo     orbit@singular.test /  singular-demo
echo.
echo   This window always starts at the sign-in screen, and signing in here
echo   leaves the main window's saved session untouched.
echo.
echo   The temporary directory is left behind on purpose so you can inspect it;
echo   Windows clears %%TEMP%% on its own schedule.
endlocal
