#!/bin/bash

REPO_NAME=$1
REPOS_DIR="metron_repos"
BARE_REPO_NAME="bare_${REPO_NAME}"

mkdir $REPOS_DIR

# Check if the repository exists
if [ -d "${REPOS_DIR}/${REPO_NAME}" ]; then
  echo "${REPOS_DIR}/${REPO_NAME}"
else
  # Create a bare repository with the given name
  cd "${REPOS_DIR}"
  git init --bare "${BARE_REPO_NAME}"
  echo "${REPOS_DIR}/${BARE_REPO_NAME}"
fi
