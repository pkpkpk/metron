### AWS Credentials

```sh
export AWS_PROFILE=your_profile

export AWS_REGION=your_region_1
# or
export AWS_SDK_LOAD_CONFIG=true

node metron.js --create-webhook
```

Credentials follow the [javascript SDK](https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/configuring-the-jssdk.html) conventions. By default [the SDK does not load](https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/setting-region.html)

### Usage

```sh
node metron.js --create-webhook
# node metron.js --delete-webhook
# node metron.js --ssh-dst
# node metron.js --status
# node metron.js --instance-status
# node metron.js --sleep
# node metron.js --push-to-instance
# node metron.js --run-command
# node metron.js --update-instance
# node metron.js --update-webhook-stack
# node metron.js --update-webhook-cmd
# node metron.js --verify-webhook
# node metron.js --webhook-config
# node metron.js --list-bucket
```
