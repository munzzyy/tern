#!/bin/bash
# Publishes site/ to the gh-pages branch, which GitHub Pages serves at https://tern.munzzyy.dev/.
# The branch is made by git subtree split, which makes the same commits from the same history, so each
# publish is a plain push on top of the last one.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain -- site)" ] || { echo "commit site/ first"; exit 1; }
node tools/check-site.js
git subtree split --prefix site -b gh-pages-next > /dev/null
git push origin gh-pages-next:gh-pages
git branch -D gh-pages-next > /dev/null
