@echo off
REM ===========================================================================
REM  Clean rebuild. Slower than build.bat -- reach for it when a build starts
REM  behaving oddly, or after changing Gradle files or dependency versions.
REM
REM  Also stops anything still running, because a live app holds a lock on the
REM  jars `clean` is about to delete.
REM ===========================================================================

setlocal
call "%~dp0_env.bat" || exit /b 1
echo   JDK: %JAVA_HOME%
echo.

echo   Stopping anything still running...
taskkill /f /fi "WINDOWTITLE eq Singular server*" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Singular client*" >nul 2>&1
for /f "tokens=2 delims=," %%P in ('tasklist /fi "imagename eq java.exe" /fo csv /nh 2^>nul') do (
  taskkill /f /pid %%~P >nul 2>&1
)
ping -n 3 127.0.0.1 >nul

echo.
echo ============================================================
echo   SERVER  clean build
echo ============================================================
pushd "%PROJECT_ROOT%\server"
call gradlew.bat clean build --console=plain
if errorlevel 1 ( popd & echo. & echo   Server rebuild FAILED. & exit /b 1 )
popd

echo.
echo ============================================================
echo   CLIENT  clean build
echo ============================================================
pushd "%PROJECT_ROOT%\client"
call gradlew.bat clean :composeApp:desktopJar :composeApp:exportLaunchArgs --console=plain
if errorlevel 1 ( popd & echo. & echo   Client rebuild FAILED. & exit /b 1 )
popd

echo.
echo   Clean rebuild OK.
endlocal
