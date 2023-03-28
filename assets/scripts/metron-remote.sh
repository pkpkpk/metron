
arg="$1"

timestamp=$(date +"%Y-%m-%d %H:%M:%S")

echo "[$timestamp] webhook started" >> metron_webhook.log
node metron_webhook_handler.js "$arg" > metron.out 2>> metron_webhook.log

exit_code=$?
timestamp=$(date +"%Y-%m-%d %H:%M:%S")
echo "[$timestamp] webhook exited with code $exit_code" >> metron_webhook.log

exit $exit_code
