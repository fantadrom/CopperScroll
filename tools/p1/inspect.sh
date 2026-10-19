#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
if [[ $# != 1 ]]; then echo 'usage: bash tools/p1/inspect.sh SYNTHETIC_FILE' >&2; exit 2; fi
# Gradle performs compilation; core files are never opened for writing by the inspector.
bash ./gradlew :app:compileDebugKotlin --console=plain >&2
p1_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
p1_classes=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
p1_cp="$p1_classes"
for p1_spec in 'co.nstant.in/cbor/0.9/cbor-0.9.jar' 'com.ibm.icu/icu4j/76.1/icu4j-76.1.jar' 'org.jetbrains.kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.jar'; do
    p1_dir="${p1_spec%/*}"; p1_name="${p1_spec##*/}"
    p1_jar="$(find "$p1_cache/$p1_dir" -name "$p1_name" -type f -print -quit)"
    [[ -n "$p1_jar" ]] || { echo 'missing pinned dependency' >&2; exit 2; }
    p1_cp="$p1_cp:$p1_jar"
done
mkdir -p work/p1-cli
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$p1_cp" -d work/p1-cli tools/p1/Inspector.java
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx256m -cp "work/p1-cli:$p1_cp" Inspector "$1"
