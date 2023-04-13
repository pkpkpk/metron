json_file="$1"
should_shutdown="$2"

LOG_FILE="webhook.log"
timestamp=$(date +"%Y-%m-%d %H:%M:%S")

sudo -u ec2-user touch $LOG_FILE

echo "[$timestamp] webhook started with JSON file: $json_file" | tee -a $LOG_FILE >&2
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

# node "$SCRIPT_DIR/metron_webhook_handler.js" $json_file 2> >(tee -a "$LOG_FILE" >&2) | tee -a "$LOG_FILE"
# exit_code=${PIPESTATUS[0]}
temp_file=$(mktemp)
node "$SCRIPT_DIR/metron_webhook_handler.js" $json_file 2> "$temp_file" | tee -a "$LOG_FILE"
exit_code=${PIPESTATUS[0]}
cat "$temp_file" >&2 >> "$LOG_FILE"
rm "$temp_file"

timestamp=$(date +"%Y-%m-%d %H:%M:%S")
echo "[$timestamp] webhook exited with code $exit_code" | tee -a $LOG_FILE >&2

if [[ "$should_shutdown" ]]; then
  echo "[$timestamp] shutting down" | tee -a $LOG_FILE >&2
  sudo shutdown -h now
fi

exit $exit_code
