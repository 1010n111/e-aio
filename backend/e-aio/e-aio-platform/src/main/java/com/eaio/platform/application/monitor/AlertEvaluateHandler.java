package com.eaio.platform.application.monitor;

import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import org.springframework.stereotype.Component;

/** One-minute built-in evaluator registered through the existing scheduler registry. */
@Component
public class AlertEvaluateHandler implements JobHandler {
  public static final String CODE = "platform.alert.evaluate";
  private final AlertAppService alerts;

  public AlertEvaluateHandler(AlertAppService alerts) {
    this.alerts = alerts;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public void execute(JobContext context) {
    alerts.evaluate();
  }
}
