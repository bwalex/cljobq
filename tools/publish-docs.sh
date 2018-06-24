#!/bin/bash

export AUTODOC_CMD="lein codox"
export AUTODOC_DIR="target/doc"
export AUTODOC_REMOTE="origin"
export AUTODOC_BRANCH="gh-pages"

tools/autodoc.sh
