# CDK SQS and Lambda example

This is a real AWS CDK TypeScript application. It synthesizes an SQS queue, a Java 21
Lambda function, and an SQS event-source mapping into `cdk.out/` for Ares to parse.

Build the existing Java Lambda artifact and synthesize the Cloud Assembly with:

```bash
npm install
npm run synth
```

The example uses the local role ARN `arn:aws:iam::000000000000:role/lambda-local`, so CDK does
not add an IAM resource to the assembly. Ares provisions the SQS queue, Lambda function, and
SQS-to-Lambda event-source mapping.

To preview the generated assembly through Ares:

```bash
ares deploy ./examples/cdk-sqs-lambda --dry-run
```
