#!/usr/bin/env sh
# Lightweight Gradle bootstrapper. The GitHub Actions workflow uses the same Gradle 8.9 version.
set -eu
VERSION="8.9"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DEST="$GRADLE_USER_HOME/wrapper/dists/gradle-$VERSION-bin/sudoku-overlay"
GRADLE="$DEST/gradle-$VERSION/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  mkdir -p "$DEST"
  ZIP="$DEST/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  else
    echo "curl or wget is required to download Gradle $VERSION" >&2
    exit 1
  fi
  unzip -q -o "$ZIP" -d "$DEST"
fi
exec "$GRADLE" "$@"
