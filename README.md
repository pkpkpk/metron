

create a empty gh repo with a Dockerfile for example:

```Dockerfile
FROM openjdk:latest # or something lighter weight. must have build step though
CMD ["echo", "Hello, world!"]
```

In the metron repository:

```sh
npm install aws-sdk # v2 for ide reasons

export AWS_PROFILE=your_profile

export AWS_REGION=your_region_1
# or
export AWS_SDK_LOAD_CONFIG=true

node metron.js --create-webhook -k "registered-keypair-name"
```

Profile needs to create bucket/instance/lamdba/roles (see assets/templates/webhook.json)

Credentials follow the [javascript SDK](https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/configuring-the-jssdk.html) conventions. By default [the SDK does not load](https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/setting-region.html) the region set in default profile unless it is specified in environment


```sh
node metron.js -h
node metron.js --create-webhook
node metron.js --delete-webhook
node metron.js --ssh
node metron.js --status
```
