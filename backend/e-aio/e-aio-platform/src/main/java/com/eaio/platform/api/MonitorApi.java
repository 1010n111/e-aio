package com.eaio.platform.api;

import com.eaio.platform.api.dto.AlertDTO;
import com.eaio.platform.api.dto.MetricSnapshotDTO;
import java.util.List;
import java.util.Map;

/** Read only monitoring facade for other modules. */
public interface MonitorApi {
  MetricSnapshotDTO metrics();

  Map<String, String> health();

  List<AlertDTO> alerts();
}
