#!/bin/bash
# PostToolUse-хук: после правки кода в фоне запускает headless Claude, который
# приводит README.md и docs/ в соответствие с кодом, коммитит и пушит только доки.
# Правки подряд склеиваются: агент стартует через DEBOUNCE секунд тишины.

# Внутри самого агента хук не срабатывает, иначе его правки зациклят запуск.
[ -n "$HEAT_DOCS_SYNC" ] && exit 0

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FILE=$(jq -r '.tool_input.file_path // .tool_response.filePath // ""')

case "$FILE" in
  "$ROOT"/src/main/*|"$ROOT"/pom.xml|"$ROOT"/docker-compose.yml|"$ROOT"/Dockerfile|"$ROOT"/.gitlab-ci.yml) ;;
  *) exit 0 ;;
esac

DEBOUNCE=90
STAMP="$ROOT/.claude/docs-sync.stamp"
LOCK="$ROOT/.claude/docs-sync.lock"
LOG="$ROOT/.claude/docs-sync.log"
date +%s > "$STAMP"

(
  sleep "$DEBOUNCE"
  # запускается только последний из серии вызовов
  [ $(( $(date +%s) - $(cat "$STAMP") )) -lt "$DEBOUNCE" ] && exit 0
  mkdir "$LOCK" 2>/dev/null || exit 0
  trap 'rmdir "$LOCK"' EXIT
  cd "$ROOT" || exit 0
  {
    echo "=== $(date '+%F %T') docs-sync"
    HEAT_DOCS_SYNC=1 /usr/local/bin/claude -p "$(cat "$ROOT/.claude/hooks/docs-sync-prompt.md")" \
      --allowedTools "Read,Glob,Grep,Edit,Write,Bash(git diff:*),Bash(git log:*),Bash(git status:*),Bash(git add:*),Bash(git commit:*),Bash(git push:*)"
  } >> "$LOG" 2>&1
) > /dev/null 2>&1 &

exit 0
