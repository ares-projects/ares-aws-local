# CDK SQS and Lambda example

This is a real AWS CDK TypeScript application. It synthesizes an SQS queue and a Java 21
Lambda function into `cdk.out/` for Ares to parse.

Build the existing Java Lambda artifact and synthesize the Cloud Assembly with:

```bash
npm install
npm run synth
```

The example uses the local role ARN `arn:aws:iam::000000000000:role/lambda-local`, so CDK does
not add an IAM resource to the assembly. Ares currently provisions the SQS and Lambda resources;
Lambda-to-SQS event source mappings are the next integration slice.

To preview the generated assembly through Ares:

```bash
ares deploy ./examples/cdk-sqs-lambda --dry-run
```
