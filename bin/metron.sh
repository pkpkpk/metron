#!/bin/bash

SCRIPT_DIR=$(dirname "$(readlink -f "$0")")
ROOT_PATH=$(dirname $SCRIPT_DIR)

case "$(uname -s)" in
    Linux*) LOG_DIR="$HOME/.local/share/metron" ;;
    Darwin*) LOG_DIR="$HOME/Library/Logs/Metron" ;;
    *) LOG_DIR="$HOME/.log/metron" ;;
esac

LOG_FILE="$LOG_DIR/metron.log"

ssh=false
node_args=()
while [[ $# -gt 0 ]]
do
    case "$1" in
        --log)
            echo "$LOG_FILE"
            exit 0
            ;;
        --ssh)
            ssh=true
            shift
            ;;
        *)
            node_args+=("$1")
            shift
            ;;
    esac
done

if [ "$ssh" = true ]
then
    ssh_args=$(node $ROOT_PATH/dist/metron_cli.js --ssh)
    exit_code=$?
    if [ $exit_code -eq 0 ]
    then
        key=$(echo "$ssh_args" | awk '{print $1}')
        ip=$(echo "$ssh_args" | awk '{print $2}')
        echo "starting ssh session with $ip"
        ssh -i $key $ip
        exit_code=$?
    fi
    exit "$exit_code"
fi

mv -f "$LOG_FILE.2" "$LOG_FILE.3" 2>/dev/null
mv -f "$LOG_FILE.1" "$LOG_FILE.2" 2>/dev/null
mv -f "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null

mkdir -p "$LOG_DIR"
touch "$LOG_FILE"

echo "Calling metron_cli.js with args: $node_args" >> "$LOG_FILE"

node $ROOT_PATH/dist/metron_cli.js "${node_args[@]}" 2> >(tee -a "$LOG_FILE" >&2) | tee -a "$LOG_FILE"

exit_code=${PIPESTATUS[0]}
echo "Exit code: $exit_code" >> "$LOG_FILE"

exit "$exit_code"
