arg="$1"

timestamp=$(date +"%Y-%m-%d %H:%M:%S")

echo "[$timestamp] webhook started" | tee -a metron_webhook.log
export AWS_PROFILE=metron
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
node "$SCRIPT_DIR/metron_webhook_handler.js" "$arg" 2>&1 | tee -a metron_webhook.log

exit_code=$?
timestamp=$(date +"%Y-%m-%d %H:%M:%S")
echo "[$timestamp] webhook exited with code $exit_code" >> metron_webhook.log

exit $exit_code
