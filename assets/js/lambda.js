const secret = process.env.WEBHOOK_SECRET;
const region = process.env.REGION;
const instanceId = process.env.INSTANCE_ID;
const maxWaitTime = process.env.MAX_WAIT_TIME || 500;
const shouldWaitForInvocation = process.env.SHOULD_WAIT_FOR_INVOCATION;

const crypto = require("crypto");
const ec2 = require("@aws-sdk/client-ec2");
const { SSMClient, StartSessionCommand, SendCommandCommand,
        GetCommandInvocationCommand,TerminateSessionCommand } = require('@aws-sdk/client-ssm');


function verify_signature(body, request_sig){
    var HMAC = crypto.createHmac("sha256", secret);
    HMAC.update(body);
    var hashsum = HMAC.digest("hex");
    return request_sig == "sha256="+ hashsum;
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
    var signature = event.headers["x-hub-signature-256"];
    var is_verified = verify_signature(event.body, signature);

    if (!is_verified) {
      return {statusCode: 401, body: "bad signature"};
    } else {

      try {
        await waitForInstanceOk();
        const ssm = new SSMClient();
        const startSessionCommand = new StartSessionCommand({Target: instanceId});
        const startSessionData = await ssm.send(startSessionCommand);
        const sessionId = startSessionData.SessionId;
        console.log('SSM session started successfully:', sessionId);

        event.body = JSON.parse(event.body);

        var cmd = `node metron_server.js '${JSON.stringify(event)}'`;

        const sendCommandParams = {
          DocumentName: 'AWS-RunShellScript',
          Parameters: {
            workingDirectory: ["/home/ec2-user"],
            commands: [
            cmd
            // 'shutdown -h now'
            ]
          },
          Targets: [{Key: 'InstanceIds', Values: [instanceId]}]
        };

        const sendCommandCommand = new SendCommandCommand(sendCommandParams);
        const sendCommandData = await ssm.send(sendCommandCommand);
        const commandId = sendCommandData.Command.CommandId;

        if (!shouldWaitForInvocation){
          return {status: 200, body: sendCommandData};
        } else {

          const getCommandInvocationParams = {
            CommandId: commandId,
            InstanceId: instanceId
          };

          let stdout = "";
          let stderr = "";
          let commandResult;

          await new Promise(resolve => setTimeout(resolve, 1000))

          while (true) {
            const getCommandInvocationCommand = new GetCommandInvocationCommand(getCommandInvocationParams);
            console.log("awaiting invocation...");
            const getCommandInvocationResponse = await ssm.send(getCommandInvocationCommand);
            if (getCommandInvocationResponse.Status === "InProgress") {
              await new Promise(resolve => setTimeout(resolve, 5000)); // Wait 5 seconds before checking again
            } else {
              stdout += getCommandInvocationResponse.StandardOutputContent;
              stderr += getCommandInvocationResponse.StandardErrorContent;
              commandResult = getCommandInvocationResponse;
              break;
            }
          }

          const endSessionParams = {
            SessionId: sessionId,
            Target: instanceId
          };

          const terminateSessionCommand = new TerminateSessionCommand(endSessionParams);
          await ssm.send(terminateSessionCommand);

          return {statusCode: 200, body: commandResult}
        }
      } catch (err) {
        console.error(err)
        return {
          statusCode: 500,
          body: err
        };
      }
    }
};