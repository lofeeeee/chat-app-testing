@echo off
REM ===========================================================================
REM  Incremental build of both projects.
REM
REM  The two Gradle builds are independent on purpose: a missing Android SDK
REM  must never be able to block the backend. The client build skips its
REM  Android target automatically when no SDK is present.
REM ===========================================================================

setlocal
call "%~dp0_env.bat" || exit /b 1
echo   JDK: %JAVA_HOME%
echo.

echo ============================================================
echo   SERVER  (Kotlin + Spring Boot)
echo ============================================================
pushd "%PROJECT_ROOT%\server"
call gradlew.bat build --console=plain
if errorlevel 1 (
  popd
  echo.
  echo   Server build FAILED.
  exit /b 1
)
popd

echo.
echo ============================================================
echo   CLIENT  (Compose Multiplatform, desktop)
echo ============================================================
pushd "%PROJECT_ROOT%\client"
REM exportLaunchArgs writes build\singular-args.txt so start_another.bat can
REM launch extra windows without Gradle -- two concurrent `gradlew run` calls
REM deadlock on the project lock.
call gradlew.bat :composeApp:desktopJar :composeApp:exportLaunchArgs --console=plain
if errorlevel 1 (
  popd
  echo.
  echo   Client build FAILED.
  exit /b 1
)
popd

echo.
echo   Build OK. Run start.bat to launch everything.
endlocal
