@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
set GRADLE_USER_HOME=%APP_HOME%\.gradle
if not "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%GRADLE_USER_HOME:"=%
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
set GRADLE_OPTS=%GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%"
java %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
:end
@rem End local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" endlocal
:omega
