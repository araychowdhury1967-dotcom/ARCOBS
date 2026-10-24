@echo off
set APP_HOME=%~dp0
if defined JAVA_HOME (set JAVA=%JAVA_HOME%\bin\java.exe) else (set JAVA=java.exe)
"%JAVA%" %JAVA_OPTS% %GRADLE_OPTS% -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
