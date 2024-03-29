const secret = process.env.WEBHOOK_SECRET;
const region = process.env.REGION;
const instanceId = process.env.INSTANCE_ID;
const maxWaitTime = process.env.MAX_WAIT_TIME ? parseInt(process.env.MAX_WAIT_TIME) : 500;
const shouldShutdownInstance = ("true" == process.env.SHOULD_SHUTDOWN_INSTANCE) ? true : false;

const crypto = require("crypto");
const ec2 = require("@aws-sdk/client-ec2");
const { SSMClient, StartSessionCommand, SendCommandCommand,
        GetCommandInvocationCommand,TerminateSessionCommand } = require('@aws-sdk/client-ssm');

function verify_signature(body, request_sig){
    var HMAC = crypto.createHmac("sha256", secret);
    HMAC.update(body);
    return request_sig == "sha256="+ HMAC.digest("hex");
}

async function waitForInstanceOk() {
  const ec2Client = new ec2.EC2Client({ region });
  try {
    const startInstanceResult = await ec2Client.send(new ec2.StartInstancesCommand({
      InstanceIds: [instanceId]
    }));
    var wait_params = { client: ec2Client, delay: 5, maxWaitTime};
    await ec2.waitUntilInstanceStatusOk(wait_params, {InstanceIds: [instanceId]});
    return Promise.resolve(startInstanceResult);
  } catch (error) {
    return Promise.reject(error);
  }
}

exports.handler = async(event, _ctx) => {
    if (!("application/json" === event.headers["content-type"])) {
      return {statusCode: 401, body: "bad content-type, expected json"};
    }
    if (!verify_signature(event.body, event.headers["x-hub-signature-256"])) {
      return {statusCode: 402, body: "bad signature"};
    }

    event.body = JSON.parse(event.body);
    const event_type = event.headers["x-github-event"];

    if (!((event_type === "ping") || ((event_type === "push") && (event.body.ref  === "refs/heads/metron")) )){
      return {status: 204, body: "no-op event for ref "+event.body.ref}
    }
    try {
      await waitForInstanceOk();
      const ssm = new SSMClient();
      const startSessionCommand = new StartSessionCommand({Target: instanceId});
      const startSessionData = await ssm.send(startSessionCommand);
      const sessionId = startSessionData.SessionId;
      const timestamp = (new Date()).toISOString().replace(/:/g, '-').replace(/\.\d{3}/, '');
      const fileName =  timestamp + '_' + event.body.repository.name + '_' + event_type + '.json';
      var prep_cmd = `mkdir events && echo '${JSON.stringify(event)}' > events/'${fileName}'`;
      var base_cmd = prep_cmd + " && " + `./bin/webhook.sh events/'${fileName}'`;
      var cmd = shouldShutdownInstance ? base_cmd + " true" : base_cmd;
      const sendCommandParams = {
        DocumentName: 'AWS-RunShellScript',
        Parameters: {workingDirectory: ["/home/ec2-user"],
                     commands: [cmd]},
        Targets: [{Key: 'InstanceIds', Values: [instanceId]}]
      };
      const sendCommandCommand = new SendCommandCommand(sendCommandParams);
      const sendCommandData = await ssm.send(sendCommandCommand);
      const commandId = sendCommandData.Command.CommandId;
      const endSessionParams = {SessionId: sessionId, Target: instanceId};
      const terminateSessionCommand = new TerminateSessionCommand(endSessionParams);
      ssm.send(terminateSessionCommand);
    } catch (err) {
      console.error(err)
      return {
        statusCode: 500,
        body: err
      };
    }

};