@echo off
REM ===========================================================================
REM  Opens one more client window against the server that's already running.
REM
REM  For testing two accounts side by side: sign this one in as the other user
REM  and watch messages and typing indicators cross between the windows.
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

echo   Opening another Singular window...
start "Singular client" cmd /k ""%JAVA_EXE%" "@%ARGFILE%""

echo.
echo     nova@singular.test  /  singular-demo
echo     orbit@singular.test /  singular-demo
echo.
echo   Sign each window in as a different account to see live delivery.
endlocal
