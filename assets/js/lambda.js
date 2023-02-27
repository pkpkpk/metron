var secret = process.env.WEBHOOK_SECRET;
var region = process.env.REGION;
var instanceId = process.env.INSTANCE_ID;

var crypto = require("crypto");
const ec2 = require("@aws-sdk/client-ec2");
const {
        SSMClient, StartSessionCommand, SendCommandCommand,
        GetCommandInvocationCommand,TerminateSessionCommand
      } = require('@aws-sdk/client-ssm');


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


exports.handler = async(event, _ctx) => {
    var signature = event.headers["x-hub-signature-256"];
    var is_verified = verify_signature(event.body, signature);

    if (!is_verified) {
      return {statusCode: 401, body: "bad signature"};
    } else {

      try {
        // this path will fail if the instance agent has not yet registered
        // with the SSM 'fleet'... there does not appear to be a programmatic
        // way to wait for this
        await waitForInstanceRunning();
        const ssm = new SSMClient();
        const startSessionCommand = new StartSessionCommand({Target: instanceId});
        const startSessionData = await ssm.send(startSessionCommand);
        const sessionId = startSessionData.SessionId;
        console.log('SSM session started successfully:', sessionId);

        // var event_json = JSON.stringify(event);

        event.body = JSON.parse(event.body);

        var cmd = `node metron_server.js '${JSON.stringify(event)}'`;

        const sendCommandParams = {
          DocumentName: 'AWS-RunShellScript',
          Parameters: {
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
        console.log('SSM command executed successfully:', commandId);


        const getCommandInvocationParams = {
          CommandId: commandId,
          InstanceId: instanceId
        };

        let stdout = "";
        let stderr = "";
        let commandResult;

        // avoiding 'InvocationDoesNotExist' error
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
      } catch (err) {
        return {
          statusCode: 500,
          body: err
        };
      }
    }
};