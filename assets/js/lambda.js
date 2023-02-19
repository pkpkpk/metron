var secret = process.env.WEBHOOK_SECRET;
var region = process.env.REGION;
var instanceId = process.env.INSTANCE_ID;
var command = process.env.COMMAND;

var crypto = require("crypto");
var ec2 = require("@aws-sdk/client-ec2");
var ssm = require("@aws-sdk/client-ssm");

function verify_signature(body, request_sig){
    var HMAC = crypto.createHmac("sha256", secret);
    HMAC.update(body);
    var hashsum = HMAC.digest("hex");
    return request_sig == "sha256="+ hashsum;
}

async function waitForInstanceRunning() {
  const ec2Client = new ec2.EC2Client({ region });
  try {
    const startInstanceResult = await ec2Client.send(new ec2.StartInstancesCommand({
      InstanceIds: [instanceId]
    }));

    // if instance is already running we are ready to go
    if (startInstanceResult.StartingInstances[0].CurrentState.Name === 'running') {
      return Promise.resolve(startInstanceResult);
    }
    // else wait for instance to wake up before allowing SSM to proceed
    var wait_params = {
      client: ec2Client,
      delay: 5,
      maxWaitTime: 120
    };
    await ec2.waitUntilInstanceRunning(wait_params, {InstanceIds: [instanceId]});
    return Promise.resolve(startInstanceResult);
  } catch (error) {
    return Promise.reject(error);
  }
}

var ssm_params = {
  DocumentName: "AWS-RunShellScript",
  InstanceIds: [instanceId],
  OutputS3BucketName: "metronbucket",
  Parameters: {
    commands: [command],
    workingDirectory: ["/home/ec2-user"]
  }
};

exports.handler = async(event, _ctx) => {
    var signature = event.headers["x-hub-signature-256"];
    var is_verified = verify_signature(event.body, signature);

    if (!is_verified) {
      return {statusCode: 401, body: "bad signature"};
    } else {
      //TODO look for ping
      try {
        await waitForInstanceRunning();
        const ssmClient = new ssm.SSMClient({ region });
        console.log("calling ssm");
        var body = JSON.parse(event.body);
        ssm_params.OutputS3KeyPrefix = body.repository.name;

        ssmClient.send(new ssm.SendCommandCommand(ssm_params));
        return {statusCode: 200, body: "command sent"}
        // var cmd = await ssmClient.send(new ssm.SendCommandCommand(ssm_params));
        // return {statusCode: 200, body: cmd};
      } catch (err) {
        return {
          statusCode: 500,
          body: JSON.stringify(err.message)
        };
      }
    }
};