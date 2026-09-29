#!/bin/bash
# rename.sh <Old> <New>: gives the app another name everywhere: package, folders, file names, text.
# Test fixtures are left alone, because some of them are signed and a changed byte breaks them.
set -euo pipefail
OLD="${1:?usage: rename.sh <Old> <New>}"; NEW="${2:?usage: rename.sh <Old> <New>}"
[[ "$OLD" =~ ^[A-Z][a-z]+$ && "$NEW" =~ ^[A-Z][a-z]+$ ]] || { echo "Names are one capitalised word of letters, like Stamp"; exit 2; }
cd "$(git rev-parse --show-toplevel)"
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash first: the rename wants a clean tree"; exit 2; }
old="${OLD,,}"; new="${NEW,,}"; OLDUP="${OLD^^}"; NEWUP="${NEW^^}"

keep() { [[ "$1" == core/src/test/resources/* || "$1" == app/src/androidTest/assets/* || "$1" == tools/make-test-repo.sh || "$1" == tools/rename.sh ]]; }

# Folders and files first, deepest first, so a parent's move never hides a child.
git ls-files | while IFS= read -r f; do keep "$f" || echo "$f"; done | grep -i "$old" | awk -F/ '{print NF, $0}' | sort -rn | cut -d' ' -f2- | while IFS= read -r f; do
  [ -e "$f" ] || continue
  target="$(echo "$f" | sed "s/$OLD/$NEW/g; s/$old/$new/g; s/$OLDUP/$NEWUP/g")"
  [ "$f" = "$target" ] && continue
  mkdir -p "$(dirname "$target")"
  git mv "$f" "$target"
done
find . -depth -type d -empty -not -path './.git/*' -delete 2>/dev/null || true

git ls-files | while IFS= read -r f; do
  keep "$f" && continue
  [ -f "$f" ] || continue
  grep -Iq . "$f" 2>/dev/null || continue
  grep -qi "$old" "$f" || continue
  sed -i "s/$OLD/$NEW/g; s/$old/$new/g; s/$OLDUP/$NEWUP/g" "$f"
done

echo "Still naming $OLD outside the fixtures:"
git ls-files | while IFS= read -r f; do keep "$f" || echo "$f"; done | xargs -d '\n' grep -Iil "$old" 2>/dev/null || echo "  nothing"
