package io.github.aresprojects.local.runtime.trigger.sqs;

import java.time.Duration;

@FunctionalInterface
interface SqsLambdaBatchWaiter {
    void await(Duration duration) throws InterruptedException;
}
