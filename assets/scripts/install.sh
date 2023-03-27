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
aws s3 cp s3://$bucket_name/metron_webhook_handler.js ./bin/metron_webhook_handler.js --region $region >> metron_install.log
aws s3 cp s3://$bucket_name/metron_remote_handler.js ./bin/metron_remote_handler.js --region $region >> metron_install.log
aws s3 cp s3://$bucket_name/config.edn ./bin/config.edn --region $region >> metron_install.log
aws s3 cp s3://$bucket_name/metron-remote.sh ./bin/metron-remote --region $region >> metron_install.log
aws s3 cp s3://$bucket_name/metron-webhook.sh ./bin/metron-webhook --region $region >> metron_install.log
echo "Finished copying files from bucket" >> metron_install.log

chmod +x bin/metron-webhook
chmod +x bin/metron-remote

echo "Creating aws config file" >> metron_install.log
mkdir .aws
echo "[metron]\nregion = $region" >> .aws/config

echo "Installing node s3 client" >> metron_install.log
npm install @aws-sdk/client-s3 >> metron_install.log

echo "Installation completed at $(date)" >> metron_install.log
