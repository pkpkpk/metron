#!/bin/bash

# Usage: ./sync_bare_to_non_bare.sh <repo_name> <branch_name> <short_sha>

REPO_NAME=$1
BRANCH=$2
SHORT_SHA=$3
REPOS_DIR="metron_repos"
BARE_REPO_NAME="bare_${REPO_NAME}"
NON_BARE_REPO_NAME="${REPO_NAME}"

# Check if the bare repository exists
if [ ! -d "${REPOS_DIR}/${BARE_REPO_NAME}" ]; then
  echo "Error: Bare repository not found" >&2
  exit 1
fi

# Check if the non-bare repository exists, and create it if it doesn't
if [ ! -d "${REPOS_DIR}/${NON_BARE_REPO_NAME}" ]; then
  echo "Creating non-bare repository..." >&2
  cd "${REPOS_DIR}"
  git_output=$(sudo -u ec2-user git clone "${BARE_REPO_NAME}" "${NON_BARE_REPO_NAME}" 2>&1)
  exit_code=$?
  if [ $exit_code -ne 0 ]; then
    echo "git clone failed with exit code $exit_code:" >&2
    echo "$git_output" >&2
    exit $exit_code
  fi
fi

# Move into the non-bare repository, checkout the branch and pull the latest changes
cd /home/ec2-user/"${REPOS_DIR}/${NON_BARE_REPO_NAME}"

git_output=$(git checkout "${BRANCH}" 2>&1)
exit_code=$?
if [ $exit_code -ne 0 ]; then
  echo "git checkout failed with exit code $exit_code:" >&2
  echo "$git_output" >&2
  exit $exit_code
fi

git_output=$(git pull 2>&1)
exit_code=$?
if [ $exit_code -ne 0 ]; then
  echo "git pull failed with exit code $exit_code:" >&2
  echo "$git_output" >&2
  exit $exit_code
fi

# Test the short sha
CURRENT_SHA=$(git rev-parse --short HEAD 2>&1)
if [ "${CURRENT_SHA}" = "${SHORT_SHA}" ]; then
  echo "Success: Repository is up to date" >&2
  echo "$(realpath .)"
  exit 0
else
  echo "Error: Repository is not up to date" >&2
  exit 1
fi
