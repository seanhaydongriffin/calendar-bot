#!/usr/bin/env bash
# Build TeamsReader WITHOUT Maven: download the two dependency jars, compile with
# javac, and package a runnable jar. Works on the Pi (Java 17 already installed).
# Needs: java/javac on PATH and plain `curl` (for fetching jars from Maven Central).
set -euo pipefail
cd "$(dirname "$0")"

JSON_JAR="lib/json-20240303.jar"
MAIL_JAR="lib/javax.mail-1.6.2.jar"
JSON_URL="https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
MAIL_URL="https://repo1.maven.org/maven2/com/sun/mail/javax.mail/1.6.2/javax.mail-1.6.2.jar"

mkdir -p lib build
[ -f "$JSON_JAR" ] || { echo "Fetching org.json..."; curl -fsSL -o "$JSON_JAR" "$JSON_URL"; }
[ -f "$MAIL_JAR" ] || { echo "Fetching JavaMail..."; curl -fsSL -o "$MAIL_JAR" "$MAIL_URL"; }

echo "Compiling..."
rm -rf build/classes && mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac -encoding UTF-8 -d build/classes -cp "$JSON_JAR:$MAIL_JAR" @build/sources.txt

echo "Packaging teamsreader.jar..."
cat > build/MANIFEST.MF <<'EOF'
Main-Class: teamsreader.Main
Class-Path: lib/json-20240303.jar lib/javax.mail-1.6.2.jar
EOF
jar cfm teamsreader.jar build/MANIFEST.MF -C build/classes teamsreader

echo
echo "Done. Run with:  ./run.sh"
echo "(teamsreader.jar + the lib/ folder must stay together)"
