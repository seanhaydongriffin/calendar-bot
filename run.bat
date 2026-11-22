@echo off
REM Windows runner for the built fat jar (mvn package -> target\teamsreader-java.jar).
REM Uses the config/secret files and curl-impersonate.exe in this folder's parent
REM (C:\dwn\curl-impersonate-2.3.2). Run from anywhere; paths are absolute.

set "TEAMSREADER_HOME=C:\dwn\curl-impersonate-2.3.2"
set "TEAMSREADER_CURL=C:\dwn\curl-impersonate-2.3.2\curl-impersonate.exe"
set "TEAMSREADER_IMPERSONATE=chrome136"

REM Prefer the VS Code "Java: Export Jar..." output; fall back to the Maven fat jar.
set "JAR=%~dp0teamsreader-java.jar"
if not exist "%JAR%" set "JAR=%~dp0target\teamsreader.jar"
if not exist "%JAR%" (
    echo Jar not found. Build it first:
    echo   VS Code: "Java: Export Jar..."  (main class teamsreader.Main)  -^> teamsreader-java.jar
    echo   or  mvn package  -^> target\teamsreader.jar
    exit /b 1
)

java -jar "%JAR%"
