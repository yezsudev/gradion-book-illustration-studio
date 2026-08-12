package com.gradion.studio;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FakePipelineExecutor {
    private final String outcome;
    private final Duration delay;

    FakePipelineExecutor(
            @Value("${gradion.pipeline.fake-outcome:success}") String outcome,
            @Value("${gradion.pipeline.fake-delay:PT0S}") Duration delay) {
        this.outcome = outcome;
        this.delay = delay;
    }

    void execute() {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The pipeline execution was interrupted.");
        }
        if ("failure".equalsIgnoreCase(outcome)) throw new IllegalStateException("The fake pipeline executor failed.");
    }
}
