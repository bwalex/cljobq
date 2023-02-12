#!/bin/bash

export AUTODOC_CMD="clojure -T:build docs"
export AUTODOC_DIR="target/doc"
export AUTODOC_REMOTE="origin"
export AUTODOC_BRANCH="gh-pages"

tools/autodoc.sh
