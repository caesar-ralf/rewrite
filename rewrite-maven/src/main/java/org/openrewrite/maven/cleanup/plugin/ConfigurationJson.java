package org.openrewrite.maven.cleanup.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ConfigurationJson {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @SuppressWarnings("unchecked")
  public static Map<String, Object> getConfigurationMap(final JsonNode configurationJsonNode) {
    return (Map<String, Object>) OBJECT_MAPPER.convertValue(configurationJsonNode, Map.class);
  }
}
