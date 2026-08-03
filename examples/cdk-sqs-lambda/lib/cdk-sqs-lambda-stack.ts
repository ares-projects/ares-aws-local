import * as path from 'node:path';
import * as cdk from 'aws-cdk-lib';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export class CdkSqsLambdaStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const queue = new sqs.Queue(this, 'Queue', {
      queueName: 'ares-cdk-sqs-lambda',
    });

    const functionRole = iam.Role.fromRoleArn(
      this,
      'LocalLambdaRole',
      'arn:aws:iam::000000000000:role/lambda-local',
      { mutable: false },
    );

    const functionAsset = path.resolve(
      __dirname,
      '../../hello-lambda/build/distributions/hello.zip',
    );
    const handler = new lambda.Function(this, 'Function', {
      runtime: lambda.Runtime.JAVA_21,
      architecture: lambda.Architecture.ARM_64,
      handler: 'example.HelloHandler',
      code: lambda.Code.fromAsset(functionAsset),
      role: functionRole,
      environment: {
        GREETING: 'Ares',
      },
      timeout: cdk.Duration.seconds(10),
      memorySize: 256,
    });

    new cdk.CfnOutput(this, 'QueueUrl', { value: queue.queueUrl });
    new cdk.CfnOutput(this, 'QueueArn', { value: queue.queueArn });
    new cdk.CfnOutput(this, 'FunctionName', { value: handler.functionName });
    new cdk.CfnOutput(this, 'FunctionArn', { value: handler.functionArn });
  }
}
