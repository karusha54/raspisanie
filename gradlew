#!/bin/sh
GRADLE_VERSION=8.9
GRADLE_DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_HOME="$GRADLE_DIST_DIR/gradle-${GRADLE_VERSION}"

if [ ! -d "$GRADLE_HOME" ]; then
    mkdir -p "$GRADLE_DIST_DIR"
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -L "$GRADLE_DIST_URL" -o "$GRADLE_DIST_DIR/gradle.zip"
    unzip -q "$GRADLE_DIST_DIR/gradle.zip" -d "$GRADLE_DIST_DIR"
    rm "$GRADLE_DIST_DIR/gradle.zip"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
