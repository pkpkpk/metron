#!/bin/bash

SCRIPT_DIR=$(dirname "$(readlink -f "$0")")
ROOT_PATH=$(dirname $SCRIPT_DIR)

ssh=false
node_args=()
while [[ $# -gt 0 ]]
do
    case "$1" in
        --ssh)
            ssh=true
            shift
            ;;
        *)
            # Pass all arguments to Node.js script
            node_args+=("$1")
            shift
            ;;
    esac
done

# Connect with SSH if requested
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

# Set up logging
log_file="metron.log"

node $ROOT_PATH/dist/metron_cli.js "${node_args[@]}" 2> >(tee -a "$log_file" >&2) | tee -a "$log_file"

exit_code=${PIPESTATUS[0]}
echo "Exit code: $exit_code" >> "$log_file"

exit "$exit_code"