const secret = process.env.WEBHOOK_SECRET;
const region = process.env.REGION;
const instanceId = process.env.INSTANCE_ID;
const maxWaitTime = process.env.MAX_WAIT_TIME ? parseInt(process.env.MAX_WAIT_TIME) : 500;
const shouldWaitForInvocation = ("true" == process.env.SHOULD_WAIT_FOR_INVOCATION) ? true : false;
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
    if (!verify_signature(event.body, event.headers["x-hub-signature-256"])) {
      return {statusCode: 401, body: "bad signature"};
    } else {
      event.body = JSON.parse(event.body);
      const event_type = event.headers["x-github-event"];
      if (!((event_type === "ping") || ((event_type === "push") && (event.body.ref  === "refs/heads/metron")) )){
        return {status: 200, body: "no-op event"}
      }
      try {
        await waitForInstanceOk();
        const ssm = new SSMClient();
        const startSessionCommand = new StartSessionCommand({Target: instanceId});
        const startSessionData = await ssm.send(startSessionCommand);
        const sessionId = startSessionData.SessionId;
        var cmds = ["export AWS_PROFILE=metron",`node metron_webhook_handler.js '${JSON.stringify(event)}'`];
        if (shouldShutdownInstance){ cmds.push('shutdown -h now') };
        const sendCommandParams = {
          DocumentName: 'AWS-RunShellScript',
          Parameters: {workingDirectory: ["/home/ec2-user"],commands: cmds},
          Targets: [{Key: 'InstanceIds', Values: [instanceId]}]
        };
        const sendCommandCommand = new SendCommandCommand(sendCommandParams);
        const sendCommandData = await ssm.send(sendCommandCommand);
        const commandId = sendCommandData.Command.CommandId;
        const endSessionParams = {SessionId: sessionId, Target: instanceId};
        const terminateSessionCommand = new TerminateSessionCommand(endSessionParams);
        if (!shouldWaitForInvocation) {
          await ssm.send(terminateSessionCommand);
          return {status: 200, body: sendCommandData};
        }
        const getCommandInvocationParams = {CommandId: commandId, InstanceId: instanceId};
        let stdout = "";
        let stderr = "";
        let getCommandInvocationResponse;
        await new Promise(resolve => setTimeout(resolve, 1000))
        while (true) {
          const getCommandInvocationCommand = new GetCommandInvocationCommand(getCommandInvocationParams);
          getCommandInvocationResponse = await ssm.send(getCommandInvocationCommand);
          if (getCommandInvocationResponse.Status === "InProgress") {
            await new Promise(resolve => setTimeout(resolve, 5000));
          } else {
            stdout += getCommandInvocationResponse.StandardOutputContent;
            stderr += getCommandInvocationResponse.StandardErrorContent;
            break;
          }
        }
        await ssm.send(terminateSessionCommand);
        return {statusCode: 200, body: getCommandInvocationResponse}
      } catch (err) {
        console.error(err)
        return {
          statusCode: 500,
          body: err
        };
      }
    }
};