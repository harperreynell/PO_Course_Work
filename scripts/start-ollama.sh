#!/bin/sh
set -eu
ollama serve &
server=$!
prepare() {
    export OLLAMA_HOST=127.0.0.1:11434
    until ollama list >/dev/null 2>&1; do sleep 1; done
    if ollama show "$OLLAMA_MODEL" >/dev/null 2>&1; then return; fi
    until ollama pull "$OLLAMA_MODEL"; do
        echo "Model download failed. Retrying in 15 seconds." >&2
        sleep 15
    done
}
prepare &
loader=$!
trap 'kill "$server" "$loader" 2>/dev/null || true' INT TERM EXIT
wait "$server"
