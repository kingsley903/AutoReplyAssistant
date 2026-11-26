#!/bin/sh
# Gradle startup script. The wrapper JAR is generated in CI if it is not committed.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
  echo "Gradle wrapper JAR is missing. Run 'gradle wrapper' with Gradle 8.7 installed first." >&2
  exit 1
fi
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi
exec "$JAVACMD" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
  -Dorg.gradle.appname=gradlew \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
