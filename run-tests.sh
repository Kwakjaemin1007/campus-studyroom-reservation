#!/usr/bin/env bash
# Gradle 없이 코어를 컴파일하고 테스트를 돌린다. JUNIT_JAR, HAMCREST_JAR로 jar 위치를 넘긴다.
set -euo pipefail
cd "$(dirname "$0")"
: "${JUNIT_JAR:?junit-4.13.2.jar 경로를 JUNIT_JAR에 넣어 주세요}"
: "${HAMCREST_JAR:?hamcrest-core-1.3.jar 경로를 HAMCREST_JAR에 넣어 주세요}"
OUT=build/manual
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"
javac --release 17 -encoding UTF-8 -d "$OUT/main" $(find core/src/main/java -name '*.java')
javac --release 17 -encoding UTF-8 -cp "$OUT/main:$JUNIT_JAR" -d "$OUT/test" $(find core/src/test/java -name '*.java')
TESTS=$(cd core/src/test/java && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g' | sort)
java -cp "$OUT/main:$OUT/test:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore $TESTS
