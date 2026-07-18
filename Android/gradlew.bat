@ECHO OFF
SETLOCAL
SET DIRNAME=%~dp0
IF "%DIRNAME%" == "" SET DIRNAME=.
SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIRNAME%

IF EXIST "%JAVA_HOME%\bin\java.exe" (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)

REM Prefer the Gradle wrapper JAR when present; otherwise fall back to system gradle.
IF EXIST "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
  "%JAVA_EXE%" -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) ELSE (
  gradle %*
)
