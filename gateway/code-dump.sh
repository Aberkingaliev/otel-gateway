#!/bin/sh
# Project exporter for AI agents (macOS-friendly, POSIX sh)

set -eu

NOW=$(date "+%Y-%m-%d_%H-%M")
OUT="project_dump_${NOW}.txt"
ROOT=$(pwd)

# redact: simple best-effort masking for common secrets
REDACT='s/\b(password|passwd|secret|token|apikey|api_key|authorization|jwt)\b([[:space:]]*[:=][[:space:]]*).*/\1\2***REDACTED***/I'

section() { printf "\n\n## %s\n\n" "$1" >> "$OUT"; }

# Start
{
  printf "# Project export (%s)\n\n" "$NOW"
  printf "_Dir_: %s\n\n" "$ROOT"
} > "$OUT"

# Environment
section "Environment"
{
  printf '```bash\n'
  uname -a 2>&1
  if command -v java >/dev/null 2>&1; then java -version 2>&1; fi
  if command -v javac >/dev/null 2>&1; then javac -version 2>&1; fi
  printf '```\n'
} >> "$OUT"

# Git context
if [ -d .git ]; then
  section "Git"
  {
    printf '```bash\n'
    printf "Branch: "
    git rev-parse --abbrev-ref HEAD 2>/dev/null
    printf "\nRemotes:\n"
    git remote -v 2>/dev/null | sed -E "$REDACT"
    printf "\nLast 20 commits:\n"
    git log --pretty=format:'%h %ad %an %s' --date=short -n 20 2>/dev/null | sed -E "$REDACT"
    printf "\nChanges (porcelain):\n"
    git status --porcelain 2>/dev/null | sed -E "$REDACT"
    printf '\n```\n'
  } >> "$OUT"
fi

# Gradle overview
if [ -x ./gradlew ]; then
  section "Gradle projects"
  { printf '```bash\n'; ./gradlew -q projects 2>&1; printf '\n```\n'; } >> "$OUT"
fi

# settings.gradle content (helps agent see module layout)
for f in settings.gradle settings.gradle.kts; do
  if [ -f "$f" ]; then
    section "Gradle settings: $f"
    {
      printf '```text\n'
      sed -E "$REDACT" "$f"
      printf '\n```\n'
    } >> "$OUT"
  fi
done

# Configuration files (mask secrets, truncate long)
section "Configuration (*.yml, *.yaml, *.properties)"
find . \( -path './.git' -o -path './.gradle' -o -path './build' -o -path './target' -o -path './node_modules' -o -path './out' \) -prune -o \
  -type f \( -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \) -print \
| while IFS= read -r f; do
    printf "### %s\n\n" "$f" >> "$OUT"
    printf '```text\n' >> "$OUT"
    # first 300 lines, redacted
    sed -E "$REDACT" "$f" | awk 'NR<=300{print} NR==300{print "... [truncated]"}' >> "$OUT"
    printf '\n```\n\n' >> "$OUT"
  done

# HTTP endpoints (annotation scan)
section "HTTP endpoints (annotation scan)"
grep -R --include='*.java' -nE '@(RestController|Controller|RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\b' . 2>/dev/null \
  | sed -E 's/^[.]\///' >> "$OUT"
printf '\n' >> "$OUT"

# DB migrations / schema hints
section "DB migrations (Flyway/Liquibase/SQL)"
find . \( -path './.git' -o -path './.gradle' -o -path './build' -o -path './target' -o -path './node_modules' -o -path './out' \) -prune -o \
  -type f \( -name 'V*.sql' -o -name '*changelog*.xml' -o -name '*.sql' \) -print \
| while IFS= read -r f; do
    printf "### %s\n\n" "$f" >> "$OUT"
    printf '```sql\n' >> "$OUT"
    awk 'NR<=200{print} NR==200{print "... [truncated]"}' "$f" >> "$OUT"
    printf '\n```\n\n' >> "$OUT"
  done

# Java sources (imports stripped)
section "Java sources (imports stripped)"
find . \( -path './.git' -o -path './.gradle' -o -path './build' -o -path './target' -o -path './node_modules' -o -path './out' \) -prune -o \
  -type f -name '*.java' -print \
| while IFS= read -r f; do
    FILE_NAME=$(basename "$f")
    PACKAGE=$(grep -m1 '^package ' "$f" | sed -E 's/^package[[:space:]]+([^;]+);/\1/')
    {
      printf '%s\n' '---'
      printf "File: %s\nPath: %s\nPackage: %s\n\n" "$FILE_NAME" "$f" "${PACKAGE:-<none>}"
      printf '```java\n'
      # show code without import lines
      grep -vE '^[[:space:]]*import[[:space:]]' "$f"
      printf '\n```\n\n'
    } >> "$OUT"
  done

# Summary
section "Summary"
{
  printf "Total Java files: %s\n" "$(find . -type f -name '*.java' | wc -l | tr -d ' ')"
  printf "Output: %s (bytes: %s)\n" "$OUT" "$(wc -c < "$OUT" | tr -d ' ')"
} >> "$OUT"

printf "Done: %s\n" "$OUT"
