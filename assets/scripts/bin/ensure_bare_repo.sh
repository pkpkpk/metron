#!/bin/bash

# we want to ensure that a bare repo exists for the given repo name.
# bare repos are given a 'bare_' prefix

REPO_NAME=$1
REPOS_DIR="remote_repos"
REPO_PATH="${REPOS_DIR}/${REPO_NAME}"
BARE_REPO_NAME="bare_${REPO_NAME}"
BARE_REPO_PATH="${REPOS_DIR}/${BARE_REPO_NAME}"

mkdir -p $REPOS_DIR

if [ -d "$BARE_REPO_PATH" ]; then
  echo "found existing bare repo" >&2
  echo "$(realpath $BARE_REPO_PATH)"
else
  echo "creating bare repo" >&2
  cd "$REPOS_DIR"
  git_output=$(git init --bare "$BARE_REPO_NAME" 2>&1)
  exit_code=$?
  if [ $exit_code -ne 0 ]; then
    echo "git init failed with exit code $exit_code:" >&2
    echo "$git_output" >&2
    exit $exit_code
  fi
  echo "$(realpath ./$BARE_REPO_NAME)"
fi
