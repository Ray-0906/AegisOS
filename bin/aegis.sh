#!/usr/bin/env bash

# aegis.sh
# Wrapper script for AegisOS CLI

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
AEGIS_JAR="$DIR/../aegis-cli/target/aegis.jar"

if [ ! -f "$AEGIS_JAR" ]; then
    echo "Error: CLI jar not found at $AEGIS_JAR"
    echo "Have you run 'mvn clean package'?"
    exit 1
fi

java -jar "$AEGIS_JAR" "$@"
