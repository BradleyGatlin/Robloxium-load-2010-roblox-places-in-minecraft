#!/bin/sh
if [ -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then exec java -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"; fi
exec gradle "$@"
