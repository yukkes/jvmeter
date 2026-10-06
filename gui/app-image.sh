#!/usr/bin/env bash
# The GUI as an app image with a Java runtime of its own (jlink + jpackage), for the OS this runs on:
# target/dist/jvmeter-VERSION-OS-ARCH.zip (.tar.gz on Linux). No Java needs to be installed to run it.
# Run after ./mvnw package with a JDK 21+ (release.yml: JDK 25, on Linux, Windows in Git Bash, and macOS).
#
#   gui/app-image.sh 0.2.0
set -euo pipefail
version=${1:?usage: gui/app-image.sh VERSION}
cd "$(dirname "$0")/.."
work=target/app-image out=target/dist
rm -rf "$work" "$out"
mkdir -p "$work/input" "$out"
cp gui/target/jvmeter-gui.jar "$work/input/"

# The GUI's modules (jdeps), and those of the agent jar it runs with this runtime to list the JVMs here
# (jvmstat through Add-Exports, which jdeps does not see); jdk.accessibility for screen readers on Windows,
# jdk.charsets for the output of ssh / kubectl in a locale that is not UTF-8.
# The JDK's classes as a CDS archive: about 10 % faster startup; the one for heaps over 32 GB is left out (+7 MB).
jlink --add-modules java.base,java.desktop,java.prefs,java.instrument,jdk.attach,jdk.internal.jvmstat,jdk.jfr,jdk.management,jdk.accessibility,jdk.charsets \
  --strip-debug --no-header-files --no-man-pages --generate-cds-archive --output "$work/runtime"
rm -f "$work"/runtime/{lib,bin}/server/classes_nocoops.jsa

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) os=windows ;;
esac
case "$(uname -m)" in
  arm64 | aarch64) arch=arm64 ;;
  *) arch=x64 ;;
esac
# macOS takes no version whose first number is 0 (and bash 3.2 rejects an empty "${arr[@]}" with set -u)
if [ "$os" = macos ]; then
  jpackage --type app-image --name jvmeter --input "$work/input" --main-jar jvmeter-gui.jar \
    --runtime-image "$work/runtime" --dest "$work"
else
  # The app's classes too, archived in the app's folder when it first exits (+17 MB on disk): about 20 % faster
  # from the second start. Named by version, as a changed jar makes the JVM ignore the archive, not rewrite it.
  # Not on macOS, where an app downloaded without a signature runs from a read-only copy: the JVM then reports
  # an error at every exit. CDS logging off: the first exit would list the classes it skips.
  jpackage --type app-image --name jvmeter --app-version "$version" --input "$work/input" --main-jar jvmeter-gui.jar \
    --runtime-image "$work/runtime" --dest "$work" \
    --java-options -Xlog:cds=off --java-options -Xlog:cds+dynamic=off --java-options -XX:+AutoCreateSharedArchive \
    --java-options "-XX:SharedArchiveFile=\$APPDIR/jvmeter-$version.jsa"
fi

name="jvmeter-$version-$os-$arch"
case "$os" in
  linux) tar -C "$work" -czf "$out/$name.tar.gz" jvmeter ;;
  macos) ditto -c -k --keepParent "$work/jvmeter.app" "$out/$name.zip" ;;
  windows) powershell -NoProfile -Command "Compress-Archive -Path '$work/jvmeter' -DestinationPath '$out/$name.zip'" ;;
esac
ls -l "$out"
