#!/bin/bash

# Directory where the script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Load .env file from project root if it exists
if [ -f "$PROJECT_ROOT/../.env" ]; then
    export $(grep -v '^#' "$PROJECT_ROOT/../.env" | xargs)
fi

# Render secret.yaml from template
envsubst < "$PROJECT_ROOT/secret.yaml.template" > "$PROJECT_ROOT/secret.yaml"

echo "✅ secret.yaml has been rendered from secret.yaml.template"
