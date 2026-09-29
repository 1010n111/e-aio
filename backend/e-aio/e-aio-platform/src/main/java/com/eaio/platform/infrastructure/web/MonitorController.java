package com.eaio.platform.infrastructure.web;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.AlertDTO;
import com.eaio.platform.api.dto.AlertIdCmd;
import com.eaio.platform.api.dto.AlertQuery;
import com.eaio.platform.api.dto.AlertRuleDTO;
import com.eaio.platform.api.dto.AlertRuleQuery;
import com.eaio.platform.api.dto.AlertRuleSaveCmd;
import com.eaio.platform.api.dto.MetricSnapshotDTO;
import com.eaio.platform.application.monitor.AlertAppService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonitorController {
  private final AlertAppService service;

  public MonitorController(AlertAppService service) {
    this.service = service;
  }

  @PostMapping("/platform/monitor/Metrics")
  @PreAuthorize("hasAuthority('platform:monitor:metrics')")
  public MetricSnapshotDTO metrics() {
    return service.metrics();
  }

  @PostMapping("/platform/monitor/Health")
  @PreAuthorize("hasAuthority('platform:monitor:metrics')")
  public Map<String, String> health() {
    return service.health();
  }

  @PostMapping("/platform/monitor/Alerts")
  @PreAuthorize("hasAuthority('platform:alert:list')")
  public List<AlertDTO> alerts() {
    return service.alerts();
  }

  @PostMapping("/platform/alert/GetPage")
  @PreAuthorize("hasAuthority('platform:alert:list')")
  public PageResult<AlertDTO> alertPage(@RequestBody(required = false) AlertQuery query) {
    return service.pageAlerts(query);
  }

  @PostMapping("/platform/alert/Ack")
  @PreAuthorize("hasAuthority('platform:alert:ack')")
  public AlertDTO ack(@Valid @RequestBody AlertIdCmd cmd) {
    return service.ack(cmd.id());
  }

  @PostMapping("/platform/alert/Resolve")
  @PreAuthorize("hasAuthority('platform:alert:resolve')")
  public AlertDTO resolve(@Valid @RequestBody AlertIdCmd cmd) {
    return service.resolve(cmd.id());
  }

  @PostMapping("/platform/alertRule/GetPage")
  @PreAuthorize("hasAuthority('platform:alertRule:list')")
  public PageResult<AlertRuleDTO> rulePage(@RequestBody(required = false) AlertRuleQuery query) {
    return service.pageRules(query);
  }

  @PostMapping("/platform/alertRule/Add")
  @PreAuthorize("hasAuthority('platform:alertRule:add')")
  public AlertRuleDTO add(@Valid @RequestBody AlertRuleSaveCmd cmd) {
    return service.addRule(cmd);
  }

  @PostMapping("/platform/alertRule/Up")
  @PreAuthorize("hasAuthority('platform:alertRule:up')")
  public AlertRuleDTO up(@Valid @RequestBody AlertRuleSaveCmd cmd) {
    return service.updateRule(cmd);
  }

  @PostMapping("/platform/alertRule/Del")
  @PreAuthorize("hasAuthority('platform:alertRule:del')")
  public void del(@RequestBody AlertRuleDeleteCmd cmd) {
    service.deleteRule(cmd.ruleCode(), cmd.version());
  }

  public record AlertRuleDeleteCmd(String ruleCode, int version) {}
}
