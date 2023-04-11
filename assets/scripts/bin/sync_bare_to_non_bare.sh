#!/bin/bash

REPO_NAME=$1
REPOS_DIR="metron_repos"
BARE_REPO_NAME="bare_${REPO_NAME}"
BRANCH=$2

if [ -d "${REPOS_DIR}/${BARE_REPO_NAME}" ]; then
  # Bare repository exists
  if [ -d "${REPOS_DIR}/${REPO_NAME}" ]; then
    # Non-bare repository exists, fetch and checkout latest changes
    cd "${REPOS_DIR}/${REPO_NAME}"
    git checkout "${BRANCH}"
    git pull
  else
    # Non-bare repository does not exist, clone it and checkout latest changes
    cd "${REPOS_DIR}"
    sudo -u ec2-user git clone --no-hardlinks "${BARE_REPO_NAME}" "${REPO_NAME}"
    cd "${REPO_NAME}"
    git checkout "${BRANCH}"
  fi
else
  # Bare repository does not exist
  echo "Error: Bare repository not found"
  exit 1
fi

