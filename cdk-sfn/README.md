# cdk-sfn

A smoother local workflow for CDK-built Lambda-based Step Functions.

## Requirements

* [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-install.html)
* [AWS SAM CLI](https://aws.amazon.com/serverless/sam/)

## Usage

```
$ ${aws-mis-dir}/cdk-sfn/local.sh <local-stack-name> <state-machine-logical-id>
```

If you've not overridden the CDK-generated logical IDs of resources, discover
eligible State Machine logical IDs using something like:

```sh
$ cdk synth stack-Local | grep -B 1 'AWS::StepFunctions::StateMachine'
```

## Local stack configuration

Some tweaks to the stack may be necessary for a smooth local experience.

### Lambda timeout

SAM Local unpacks and mounts the Lambda archive on each Lambda execution.  One
the one hand this is convenient because the process always picks up the latest
build of the Lambda, but on the other it does mean that the execution takes
somewhat longer than would normally be the case for the deployed Lambda.  CDK's
stack [environments] can be used to tweak Lambda timeouts for a "local"
environment.

### AWS service endpoint override

Lambda functions that are invoked [asynchronously][wait-for] by a Step Function
need to call back to the service to signal the completion of the task.  These
need to participate in the local environment by overriding the service endpoint
and redirecting to the local endpoint exposed by SAM local's Docker container.

### Example

```typescript
const localstack = new AStack(
  app,
  'stack-Local',
  {
    lambda: {
      environment: {
        'AWS_STATES_ENDPOINT_URL':'http://host.docker.internal:8083/'
      },
      timeout: core.Duration.seconds(180)
    }
  }
);
```

[environments]: https://docs.aws.amazon.com/cdk/latest/guide/environments.html
[wait-for]: https://docs.aws.amazon.com/step-functions/latest/dg/callback-task-sample-sqs.html#call-back-lambda-example
