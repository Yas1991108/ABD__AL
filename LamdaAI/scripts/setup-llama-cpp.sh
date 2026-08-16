#!/usr/bin/env bash
# Run this ONCE, locally, after cloning this repo and before your first
# push/build. It adds the llama.cpp submodule that app/src/main/cpp/
# CMakeLists.txt expects at app/src/main/cpp/llama.cpp.
#
# Not run automatically anywhere (no network access was available when this
# project was assembled) — this just codifies the exact commands from
# BUILD.md so you don't have to copy/paste them by hand.
#
# Usage:
#   cd LamdaAI
#   ./scripts/setup-llama-cpp.sh
#   git add .gitmodules app/src/main/cpp/llama.cpp
#   git commit -m "Add llama.cpp submodule"
#   git push

set -euo pipefail

TAG="${1:-b4200}"  # override with: ./scripts/setup-llama-cpp.sh <tag>
SUBMODULE_PATH="app/src/main/cpp/llama.cpp"

if [ -d "$SUBMODULE_PATH/.git" ] || [ -f "$SUBMODULE_PATH/.git" ]; then
  echo "llama.cpp already present at $SUBMODULE_PATH — skipping add, just checking out $TAG."
  git -C "$SUBMODULE_PATH" fetch --tags
  git -C "$SUBMODULE_PATH" checkout "$TAG"
  exit 0
fi

git submodule add https://github.com/ggerganov/llama.cpp "$SUBMODULE_PATH"
git -C "$SUBMODULE_PATH" checkout "$TAG"

echo ""
echo "Done. llama.cpp is now at $SUBMODULE_PATH, pinned to $TAG."
echo "If the native build later fails to compile against this tag, that's"
echo "almost always a renamed function in llama.h (see BUILD.md's"
echo "'If the native build fails' section), not a logic bug in llama-jni.cpp."
echo ""
echo "Next: git add .gitmodules $SUBMODULE_PATH && git commit && git push"
