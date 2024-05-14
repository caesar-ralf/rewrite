package org.openrewrite.maven.cleanup.plugin;

import static com.spotify.migrate.parentify.Tags.filterTagWithName;
import static com.spotify.migrate.parentify.Tags.getLatest;
import static com.spotify.migrate.parentify.cleanup.SpotifyAddOrUpdateChild.addOrUpdateChild;
import static org.openrewrite.xml.FilterTagChildrenVisitor.filterChildren;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.tree.Plugin;
import org.openrewrite.maven.tree.Plugin.Execution;
import org.openrewrite.xml.RemoveContentVisitor;
import org.openrewrite.xml.tree.Xml.Tag;

/**
 * Cleans up any {@code <executions />} tag from a {@code <plugin />} based on what is already
 * defined in the pom.xml {@code <pluginManagement />}.
 */
@AllArgsConstructor
public class PluginCleanupExecutions {

  private final Cursor cursor;
  private final Consumer<TreeVisitor<?, ExecutionContext>> doAfterVisit;

  Tag cleanup(Tag pluginsTag, Tag pluginTag, final Plugin projectPlugin,
      final Plugin managedPlugin) {
    if (pluginTag.getChild("executions").isPresent()) {
      // if they are equal, just remove them
      if (Objects.deepEquals(projectPlugin.getExecutions(), managedPlugin.getExecutions())) {
        return filterChildren(pluginsTag, pluginTag, filterTagWithName("executions"));
      }
      final Map<String, Execution> managedExecutionById = managedPlugin.getExecutions().stream()
          .collect(Collectors.toMap(Execution::getId, Function.identity()));
      final Map<String, Execution> projectExecutionById = projectPlugin.getExecutions().stream()
          // will only work on executions that project has and are in pluginManagement
          .filter(projectExecution -> managedExecutionById.containsKey(projectExecution.getId()))
          .collect(Collectors.toMap(Execution::getId, Function.identity()));
      for (final String id : projectExecutionById.keySet()) {
        final Optional<Tag> maybeExecutionTag = findPluginExecutionTag(pluginTag, id);
        if (maybeExecutionTag.isPresent()) {
          final Tag executionTag = maybeExecutionTag.get();
          final var projectExecution = projectExecutionById.get(id);
          final var managedExecution = managedExecutionById.get(id);
          pluginTag = cleanupExecutionConfiguration(pluginTag, executionTag, projectExecution,
              managedExecution);
          pluginTag = cleanupExecutionGoals(pluginTag, getUpdatedExecutionTag(pluginTag, id),
              projectExecution, managedExecution);
          pluginTag = cleanupExecutionPhase(pluginTag, getUpdatedExecutionTag(pluginTag, id),
              projectExecution, managedExecution);
          final var updatedExecutionTag = getUpdatedExecutionTag(pluginTag, id);
          if (isExecutionTagEmpty(updatedExecutionTag)) {
            doAfterVisit.accept(new RemoveContentVisitor<>(updatedExecutionTag, true));
          }
          pluginsTag = addOrUpdateChild(pluginsTag, pluginTag, getCursor().getParentOrThrow());
        }
      }
    }
    return pluginsTag;
  }

  private static @NotNull Tag getUpdatedExecutionTag(final Tag pluginTag, final String id) {
    return findPluginExecutionTag(pluginTag, id).orElseThrow();
  }

  private static @NotNull Optional<Tag> findPluginExecutionTag(final Tag pluginTag,
      final String id) {
    return pluginTag.getChild("executions").flatMap(e -> e.getChildren().stream()
        .filter(c -> Objects.equals(id, c.getChildValue("id").orElse(null))).findFirst());
  }

  private Tag cleanupExecutionConfiguration(Tag pluginTag, final Tag executionTag,
      final Execution projectExecution, final Execution managedExecution) {
    if (executionTag.getChild("configuration").isPresent()) {
      final var executionConfigurationTag = executionTag.getChild("configuration").get();
      final var projectExecutionConfiguration = ConfigurationJson.getConfigurationMap(
          projectExecution.getConfiguration());
      final var managedExecutionConfiguration = ConfigurationJson.getConfigurationMap(
          managedExecution.getConfiguration());
      final var execTag = new PluginCleanupConfiguration(getCursor()).cleanup(
          getLatest(pluginTag, "executions"), executionTag, executionConfigurationTag,
          projectExecutionConfiguration, managedExecutionConfiguration);
      pluginTag = addOrUpdateChild(pluginTag, execTag, getCursor().getParentOrThrow());
    }
    return pluginTag;
  }

  private static boolean isExecutionTagEmpty(final Tag updateExecutionTag) {
    return updateExecutionTag.getContent() == null || (updateExecutionTag.getContent().isEmpty()
        || (updateExecutionTag.getContent().size() == 1 && updateExecutionTag.getChild("id")
        .isPresent()));
  }

  private Tag cleanupExecutionPhase(final Tag pluginTag, final Tag executionTag,
      final Execution projectExecution, final Execution managedExecution) {
    if (executionTag.getChild("phase").isPresent()) {
      if (projectExecution.getPhase().equals(managedExecution.getPhase())) {
        return filterChildren(pluginTag, executionTag, filterTagWithName("phase"));
      }
    }
    return pluginTag;
  }

  private Tag cleanupExecutionGoals(Tag pluginTag, Tag executionTag,
      final Execution projectExecution, final Execution managedExecution) {
    // only change in case the goals are equal, otherwise we maintain whatever the user set
    if (executionTag.getChild("goals").isPresent()) {
      if (Objects.deepEquals(projectExecution.getGoals(), managedExecution.getGoals())) {
        return filterChildren(pluginTag, executionTag, filterTagWithName("goals"));
      }
    }
    return pluginTag;
  }

  private Cursor getCursor() {
    return cursor;
  }
}
