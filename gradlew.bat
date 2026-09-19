@echo off
setlocal
if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" goto wrapper
where gradle >nul 2>nul
if %errorlevel%==0 (
  echo Gradle wrapper JAR is not bundled; using installed Gradle.
  gradle %*
  exit /b %errorlevel%
)
echo ERROR: Gradle is not installed and the wrapper JAR is missing.
echo Run "gradle wrapper --gradle-version 9.5.1" once, or install Gradle 9.5.1.
exit /b 1
:wrapper
java -jar "%~dp0gradle\wrapper\gradle-wrapper.jar" %*
