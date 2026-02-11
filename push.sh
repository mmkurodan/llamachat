#!/bin/bash
set -e

git add -u
git commit -m "update"
git push

echo "=== Changes pushed and GitHub Actions triggered ==="
