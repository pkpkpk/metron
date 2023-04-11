#!/bin/bash

REPO_NAME=$1
REPOS_DIR="metron_repos"
BARE_REPO_NAME="bare_${REPO_NAME}"

# Check if the bare repository exists
if [ -d "${REPOS_DIR}/${BARE_REPO_NAME}" ]; then
  # Clone the bare repository to a new directory without the "bare_" prefix
  cd "${REPOS_DIR}"
  git clone "${BARE_REPO_NAME}" "${REPO_NAME}"
  # Remove the bare repository
  rm -rf "${BARE_REPO_NAME}"
  exit 0
elif [ -d "${REPOS_DIR}/${REPO_NAME}" ]; then
  # The repository already exists
  exit 0
else
  # Neither the bare repository nor the regular repository exists
  echo "Error: Repository not found"
  exit 1
fi
