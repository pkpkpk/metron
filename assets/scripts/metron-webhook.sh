arg="$1"

timestamp=$(date +"%Y-%m-%d %H:%M:%S")

echo "[$timestamp] webhook started" | tee -a metron_webhook.log >&2
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
output=$(node "$SCRIPT_DIR/metron_webhook_handler.js" "$arg")
exit_code=$?
echo "$output" | tee -a metron_webhook.log
timestamp=$(date +"%Y-%m-%d %H:%M:%S")
echo "[$timestamp] webhook exited with code $exit_code" | tee -a metron_webhook.log >&2

exit $exit_code
