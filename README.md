# Metron

Metron is a simple cli utility for running a docker container on an ec2 instance. Metron is explicitly intended for microbenchmarking & testing algorithms on cloud architectures and hardware configurations, not deploying apps of any kind. It removes friction from your workflow by saving you the agony of a million clicks in the AWS web console.

How it works:

1. A local nodejs script manages the lifecycle of an instance defined via a CloudFormation stack.
2. SSM is used to script the instance & configure it as a git remote for whatever repo you choose
3. With a one-liner metron will push code to your instance, build and run your container, and then return the results back to you.

### Credentials & Resources

At this time, metron expects to use an AWS profile in `.aws/credentials` that has a corresponding region set in `.aws/config`. This is simply to avoid mixing up regions and surprise AWS bills. You can override the default profile with `AWS_PROFILE` but be sure to do so every time. With the exception of an SSH key, metron does not save any local state. If you create resources in multiple regions, metron has no way of knowing that.

### Installation
Metron requires nodejs & should be installed globally. Windows is not supported.

```sh
# install globally so it can be used in multiple projects
npm install -g

metron --help
```

You can create an instance and specify [instance type](https://aws.amazon.com/ec2/instance-types/) & [cpu options](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/cpu-options-supported-instances-values.html). All instances are created with the latest Amazon Linux 2 image.

```sh
# create an instance running on a graviton 3 ARM processor
metron --create-instance --instance-type m7g.xlarge --cores 3
```

Note that some instances let you specify a virtual core count but others do not. For example the `T3` & `c6i` intel icelake instances give you access to `AVX-512` instructions but can only be used with the entire CPU. This makes them significantly more expensive, especially if you forget and leave the instance running.

```sh
# take it easy on your wallet
metron --stop
metron --status
```
### SSH

```
metron --ssh
```
__That's it!__ There's no need to pay for an elastic-ip or fish through the web console to copy the connect command everytime you restart the instance. `metron --ssh` will wake a stopped instance and retrieve the public ip address before forwarding it to your local ssh command (it does not use a node library).

Metron manages its own ssh key and will attempt to write it as `.ssh/metron.pem` or `~/metron.pem`. If you delete the key, its no big deal. It will create a new one and write the public key to the instance.

### Running containers with `--push`

The `metron --push` command will ensure that the CWD has a git remote on the instance and then `git push` the latest commit. It will then automatically build and run the container and return the stdout and stderr from the container back to you.

For this to work, the repo must have a Dockerfile, for example in your testrepo repository:

```Dockerfile
# testrepo/Dockerfile
FROM openjdk:latest
CMD ["java", "--version"]
```

From that same directory:

```sh
~/somepath/testrepo$ metron --push -q
openjdk 18.0.2.1 2022-08-18
OpenJDK Runtime Environment (build 18.0.2.1+1-1)
OpenJDK 64-Bit Server VM (build 18.0.2.1+1-1, mixed mode, sharing)
~/somepath/testrepo$ metron --stop
```

### webhook WIP




