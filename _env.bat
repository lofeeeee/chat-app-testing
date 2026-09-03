@echo off
REM ===========================================================================
REM  Shared setup for the other .bat files. Not meant to be run on its own.
REM
REM  Finds a JDK 21+. Nothing is installed on this machine and nothing is on
REM  PATH, so this also looks inside JetBrains IDEs and Android Studio, which
REM  bundle a full JBR (a real JDK with javac).
REM
REM  Caveat worth knowing: a JetBrains Runtime has no jlink or jpackage, so it
REM  can build and run the app but cannot produce an installer. For that you
REM  need a normal JDK, e.g. Temurin 21.
REM ===========================================================================

set "PROJECT_ROOT=%~dp0"
if "%PROJECT_ROOT:~-1%"=="\" set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"

REM --- already set and usable? ------------------------------------------------
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :found

REM --- ordinary JDK installs ---------------------------------------------------
for %%R in (
  "%ProgramFiles%\Eclipse Adoptium"
  "%ProgramFiles%\Java"
  "%ProgramFiles%\Microsoft"
  "%ProgramFiles%\Amazon Corretto"
  "%ProgramFiles%\Zulu"
  "%USERPROFILE%\.jdks"
) do (
  if exist %%R (
    for /f "delims=" %%D in ('dir /b /ad /o-n %%R 2^>nul') do (
      if exist "%%~R\%%D\bin\java.exe" (
        set "JAVA_HOME=%%~R\%%D"
        goto :found
      )
    )
  )
)

REM --- JBRs bundled with JetBrains IDEs ---------------------------------------
if exist "%ProgramFiles%\JetBrains" (
  for /f "delims=" %%D in ('dir /b /ad /o-n "%ProgramFiles%\JetBrains" 2^>nul') do (
    if exist "%ProgramFiles%\JetBrains\%%D\jbr\bin\java.exe" (
      set "JAVA_HOME=%ProgramFiles%\JetBrains\%%D\jbr"
      goto :found
    )
  )
)
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
  set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
  goto :found
)

echo.
echo   No JDK found.
echo.
echo   Install a JDK 21 (https://adoptium.net) or set JAVA_HOME to one you have.
echo.
exit /b 1

:found
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
exit /b 0
