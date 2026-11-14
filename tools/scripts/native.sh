#!/usr/bin/env bash
# Resource-bounded native qualification; not a substitute for the Bazel gate.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
mode="${1:-unit}"
series="${2:-3.14}"
check_series "$series"
: "${JAVA_HOME:?Set JAVA_HOME to Java 21}"
deps="${NOTIFICATION_DEPS:-$ROOT/build/deps/$series}"
python3 -I tools/scripts/fetch_deps.py --series "$series" --out "$deps"
mkdir -p build/native/"$series"
lock="${NOTIFICATION_BUILD_LOCK:-$ROOT/build/native-build.lock}"
exec 9>"$lock"
flock 9
out="$ROOT/build/native/$series"
rm -rf "$out/classes" "$out/test-classes"
mkdir -p "$out/classes" "$out/test-classes"
cp="$deps/api.jar:$deps/h2.jar:$deps/test.jar:$deps/bcprov.jar:$deps/bcpkix.jar:$deps/bcpg.jar:$deps/bcutil.jar"
mapfile -t main < <(find src/main/java -name '*.java' | sort)
"$JAVA_HOME/bin/javac" -J-Xmx256m -proc:none --release 21 -cp "$cp" -d "$out/classes" "${main[@]}"
if [[ "$mode" == build ]]; then
  python3 -I tools/scripts/native_package.py "$series" "$out/classes" "$deps/h2.jar"
  exit
fi
case "$mode" in
  unit) pattern='*Test.java' ;;
  acceptance) pattern='*IT.java' ;;
  *) die 'usage: native.sh [unit|acceptance|build] [series]' ;;
esac
mapfile -t tests < <(find src/test/java -name "$pattern" | sort)
"$JAVA_HOME/bin/javac" -J-Xmx256m -proc:none --release 21 -cp "$out/classes:$cp" -d "$out/test-classes" "${tests[@]}"
classes=()
for f in "${tests[@]}"; do
  c="${f#src/test/java/}"
  c="${c%.java}"
  classes+=("${c//\//.}")
done
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="${TMPDIR:-$ROOT/build/native}" -Xmx768m -XX:ActiveProcessorCount=2 --add-opens=java.base/java.lang=ALL-UNNAMED -cp "$out/test-classes:$out/classes:src/main/resources:$cp" org.junit.runner.JUnitCore "${classes[@]}"
