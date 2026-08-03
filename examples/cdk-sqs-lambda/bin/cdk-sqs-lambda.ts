#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { CdkSqsLambdaStack } from '../lib/cdk-sqs-lambda-stack';

const app = new cdk.App();

new CdkSqsLambdaStack(app, 'CdkSqsLambdaStack', {
  env: {
    account: '000000000000',
    region: 'us-east-1',
  },
});
