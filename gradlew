#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
exec "$JAVA" $JAVA_OPTS $GRADLE_OPTS -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
