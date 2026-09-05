@echo off
REM ===========================================================================
REM  Starts everything: database, server, and one client window.
REM
REM  Each piece opens in its own window so you can read its log. Closing a
REM  window stops that piece; the database keeps running in Docker until you
REM  run `docker stop singular-db`.
REM ===========================================================================

setlocal
call "%~dp0_env.bat" || exit /b 1

echo.
echo   Singular
echo   ========
echo   JDK: %JAVA_HOME%
echo.

REM --- 1. database ------------------------------------------------------------
docker version >nul 2>&1
if errorlevel 1 (
  echo   Docker isn't running. Start Docker Desktop and try again.
  exit /b 1
)

REM All three, not just postgres. The server refuses to start without Valkey -- it drives
REM cross-node fanout, and silently degrading to single-node would split-brain a real
REM deployment. Bringing up two of the three services just moves the failure later.
echo   [1/3] database, cache and object storage
docker compose -f "%PROJECT_ROOT%\docker-compose.yml" up -d postgres valkey minio >nul 2>&1
if errorlevel 1 (
  echo         compose failed, starting containers directly...
  docker start singular-db >nul 2>&1 || docker run -d --name singular-db ^
    -e POSTGRES_DB=singular -e POSTGRES_USER=singular -e POSTGRES_PASSWORD=singular-dev-only ^
    -p 5432:5432 postgres:17-alpine >nul 2>&1
  docker start singular-cache >nul 2>&1 || docker run -d --name singular-cache ^
    -p 6380:6379 valkey/valkey:8-alpine valkey-server --save "" --appendonly no >nul 2>&1
)

set /a _tries=0
:waitdb
docker exec singular-db pg_isready -U singular -d singular >nul 2>&1
if not errorlevel 1 goto dbready
set /a _tries+=1
if %_tries% geq 60 (
  echo         database never became ready. Try: docker logs singular-db
  exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto waitdb
:dbready

set /a _tries=0
:waitcache
docker exec singular-cache valkey-cli ping >nul 2>&1
if not errorlevel 1 goto cacheready
set /a _tries+=1
if %_tries% geq 40 (
  echo         valkey never became ready. Try: docker logs singular-cache
  exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto waitcache
:cacheready
echo         postgres 5432, valkey 6380, minio 9100 (console 9101)

REM --- 2. server --------------------------------------------------------------
set "SERVER_JAR="
for /f "delims=" %%J in ('dir /b "%PROJECT_ROOT%\server\build\libs\*.jar" 2^>nul ^| findstr /v "plain"') do (
  set "SERVER_JAR=%PROJECT_ROOT%\server\build\libs\%%J"
)
if not defined SERVER_JAR (
  echo   [2/3] server jar missing - building first
  call "%PROJECT_ROOT%\build.bat" || exit /b 1
  for /f "delims=" %%J in ('dir /b "%PROJECT_ROOT%\server\build\libs\*.jar" 2^>nul ^| findstr /v "plain"') do (
    set "SERVER_JAR=%PROJECT_ROOT%\server\build\libs\%%J"
  )
)

echo   [2/3] server
start "Singular server" cmd /k ""%JAVA_EXE%" -jar "%SERVER_JAR%""

set /a _tries=0
:waitsrv
curl -s -o nul -f http://localhost:8080/actuator/health >nul 2>&1
if not errorlevel 1 goto srvready
set /a _tries+=1
if %_tries% geq 90 (
  echo         server never came up - check the "Singular server" window
  exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto waitsrv
:srvready
echo         ready on http://localhost:8080/graphql

REM --- 3. client --------------------------------------------------------------
echo   [3/3] client
set "ARGFILE=%PROJECT_ROOT%\client\composeApp\build\singular-args.txt"
if exist "%ARGFILE%" (
  REM Launch straight from the exported classpath - no Gradle, so start_another.bat
  REM can open more windows without fighting over the project lock.
  start "Singular client" cmd /k ""%JAVA_EXE%" "@%ARGFILE%""
) else (
  echo         no launch argfile yet, going through Gradle this once
  start "Singular client" cmd /k "cd /d "%PROJECT_ROOT%\client" && gradlew.bat :composeApp:run --console=plain"
)

echo.
echo   All started.
echo.
echo     sign in    nova@singular.test  /  singular-demo
echo                orbit@singular.test /  singular-demo
echo.
echo     second window   start_another.bat
echo     stop services   docker compose down
echo.
endlocal
