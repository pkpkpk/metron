#!/bin/bash

# Set the -e option to exit on any error
set -e

# Assert that the script is called with two arguments
if [ $# -ne 2 ]; then
  echo "Usage: $0 <bucket-name> <region>"
  exit 1
fi

# Get the S3 bucket name and AWS region from the script arguments
bucket_name=$1
region=$2

# Log the start of the script to the file
echo "Starting metron server installion at $(date)" >> metron_install.log

echo "Copying files from bucket to ~/bin" >> metron_install.log
aws s3 sync s3://$bucket_name/bin ./bin --region $region >> metron_install.log
echo "Finished copying files from bucket" >> metron_install.log

echo "Setting execute permissions on .sh files" >> metron_install.log
chmod +x ./bin/*.sh >> metron_install.log

echo "Creating aws config file" >> metron_install.log
mkdir -p .aws
echo "[default]\nregion = $region" >> .aws/config

echo "Installing node s3 client" >> metron_install.log
npm install @aws-sdk/client-s3 >> metron_install.log

echo "Installation completed at $(date)" >> metron_install.log
