@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gradlew startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

@rem Catch executions from older scripts and ensure they exit cleanly.
@rem This can be removed once we can be reasonably confident that few people
@rem will be migrating directly to this new wrapper.
goto afterSafetyNet
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
goto exitWithErrorLevel
:afterSafetyNet

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem ---------------------------------------------------------------------------
@rem Rabbit Race: resolve a Gradle-capable JDK (17+) by path, never by PATH.
@rem
@rem `java` on this machine's PATH is Oracle 1.8 and Gradle 9
@rem refuses to start on it. Nothing may rely on PATH, so the wrapper resolves a
@rem JDK itself and fails loudly rather than falling back to 1.8:
@rem   JAVA_HOME, then GRADLE_JAVA_HOME, then the Adoptium/Temurin installs,
@rem   then PATH's `java` only if it is 17 or newer.
@rem ---------------------------------------------------------------------------
set "JAVA_EXE="

if defined JAVA_HOME call :tryJdk "%JAVA_HOME%"
if not defined JAVA_EXE if defined GRADLE_JAVA_HOME call :tryJdk "%GRADLE_JAVA_HOME%"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-2*") do if not defined JAVA_EXE call :tryJdk "%%~fD"
if not defined JAVA_EXE for /d %%D in ("C:\Program Files\Java\jdk-2*") do if not defined JAVA_EXE call :tryJdk "%%~fD"
if not defined JAVA_EXE call :tryPathJava

if not defined JAVA_EXE (
    1>&2 echo.
    1>&2 echo ERROR: no JDK 17 or newer was found, and Gradle 9 cannot run on older.
    1>&2 echo.
    1>&2 echo Set JAVA_HOME to a JDK 25 install ^(this machine's is
    1>&2 echo C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot^).
    1>&2 echo Refusing to fall back to PATH: PATH's java is not trusted here.
    "%COMSPEC%" /c exit 1
    goto exitWithErrorLevel
)

@rem Export the resolved JDK so Gradle's toolchain detection and child processes
@rem see it, and so nothing downstream re-derives it from PATH.
for %%H in ("%JAVA_EXE%\..\..") do set "JAVA_HOME=%%~fH"

:execute
@rem Setup the command line


@rem Execute gradlew
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*
set "GRADLE_EXIT=%ERRORLEVEL%"

@rem exit /b is the batch file's own status: without it, execution would fall
@rem through into the subroutines below and they would overwrite ERRORLEVEL.
exit /b %GRADLE_EXIT%

@rem ---------------------------------------------------------------------------
@rem JDK probes. Each is a subroutine so no delayed expansion is needed.
@rem ---------------------------------------------------------------------------
:tryJdk
@rem "%~1" is a JDK home.
if "%~1"=="" exit /b 0
call :tryJdkFile "%~1\bin\java.exe"
exit /b 0

:tryPathJava
@rem Last resort: PATH's java, accepted only when it is 17 or newer.
for /f "delims=" %%J in ('where java 2^>nul') do call :tryJdkFile "%%~fJ"
exit /b 0

:tryJdkFile
@rem "%~1" is a java.exe path.
if defined JAVA_EXE exit /b 0
if "%~1"=="" exit /b 0
if not exist "%~1" exit /b 0
set "PROBE=%~1"
call :probeJavaMajor
if %JAVA_MAJOR% LSS 17 exit /b 0
set "JAVA_EXE=%~1"
exit /b 0

:probeJavaMajor
@rem Sets JAVA_MAJOR from `%PROBE% -version`, which writes to stderr.
set "JAVA_MAJOR=0"
set "VERSION_LINE="
set "VERSION_FILE=%TEMP%\rabbitrace-java-version.txt"
if not defined TEMP set "VERSION_FILE=%APP_HOME%\build\java-version.txt"
"%PROBE%" -version > "%VERSION_FILE%" 2>&1
if not exist "%VERSION_FILE%" exit /b 0
set /p VERSION_LINE=<"%VERSION_FILE%"
if not defined VERSION_LINE exit /b 0
set "VERSION_TAIL=%VERSION_LINE:*version =%"
set "VERSION_TAIL=%VERSION_TAIL:"=%"
for /f "tokens=1 delims=. " %%m in ("%VERSION_TAIL%") do set "JAVA_MAJOR=%%m"
exit /b 0

@rem This label must not be changed. We rely on old scripts being able to jump to this point.
:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
