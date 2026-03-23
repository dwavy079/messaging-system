#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME_DEFAULT="/Users/dw079/oracleJdk-25.jdk/Contents/Home"
JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

"$JAVA_HOME/bin/javac" BackendLite.java
exec "$JAVA_HOME/bin/java" BackendLite
