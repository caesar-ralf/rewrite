package org.openrewrite.maven.cleanup.plugin;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.Plugin;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml.Tag;

/**
 * Cleans up defined {@code <plugin />} tags based on what is already defined in the pom.xml
 * {@code <pluginManagement />}, including the parents of this plugin.
 * <p>
 * The cleaning will only leave changes that are specific to a project, i.e., things that are not
 * defined already in the {@code <pluginManagement />}.
 */
@EqualsAndHashCode(callSuper = true)
@Value
public class PluginCleanupRecipe extends Recipe {

  private static final XPathMatcher PLUGINS_MATCHER = new XPathMatcher("/project/build/plugins");

  @Override
  public String getDisplayName() {
    return "Cleans up plugin duplicated configuration";
  }

  @Override
  public String getDescription() {
    return """
        Cleans up plugin duplicated configuration, i.e., removes anything that is already defined
        in the `<pluginManagement />` section, leaving only what is project specific.""".stripIndent()
        .replaceAll("\n", " ");
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getVisitor() {
    return new MavenIsoVisitor<>() {
      @Override
      public Tag visitTag(final Tag tag, final ExecutionContext executionContext) {
        Tag pluginsTag = super.visitTag(tag, executionContext);

        if (PLUGINS_MATCHER.matches(getCursor())) {
          final ResolvedPom resolvedPom = getResolutionResult().getPom();
          final Map<GroupArtifact, Plugin> managedPluginsGa = resolvedPom.getPluginManagement()
              .stream().collect(
                  Collectors.toMap(p -> new GroupArtifact(p.getGroupId(), p.getArtifactId()),
                      Function.identity()));
          final var pluginsInBothProjectAndParent = pluginsTag.getChildren().stream()
              .filter(pluginTag -> {
                final Plugin projectPlugin = checkNotNull(findPlugin(pluginTag),
                    "Unexpected error when plugin for tag should exist!");
                return managedPluginsGa.containsKey(
                    new GroupArtifact(projectPlugin.getGroupId(), projectPlugin.getArtifactId()));
              }).toList();

          for (final Tag pluginTag : pluginsInBothProjectAndParent) {
            final Plugin projectPlugin = checkNotNull(findPlugin(pluginTag),
                "Unexpected error when plugin for tag should exist!");
            var pluginGa = new GroupArtifact(projectPlugin.getGroupId(),
                projectPlugin.getArtifactId());
            var managedPlugin = managedPluginsGa.get(pluginGa);

            pluginsTag = new PluginCleanupDependencies(getCursor()).cleanup(pluginsTag, pluginTag,
                projectPlugin, managedPlugin);
            pluginsTag = new PluginCleanupExecutions(getCursor(), this::doAfterVisit).cleanup(
                pluginsTag, getUpdatedPlugin(pluginsTag, projectPlugin), projectPlugin,
                managedPlugin);
            pluginsTag = new PluginCleanupConfiguration(getCursor()).cleanup(pluginsTag,
                getUpdatedPlugin(pluginsTag, projectPlugin), projectPlugin, managedPlugin);
          }
        }
        return pluginsTag;
      }

      private Tag getUpdatedPlugin(final Tag pluginsTag, final Plugin projectPlugin) {
        return pluginsTag.getChildren("plugin").stream().filter(pluginTag ->
                Objects.equals(projectPlugin.getGroupId(),
                    pluginTag.getChildValue("groupId").orElse(null)) && Objects.equals(
                    projectPlugin.getArtifactId(), pluginTag.getChildValue("artifactId").orElseThrow()))
            .findFirst().orElseThrow(() -> new IllegalStateException(
                "Couldn't find plugin `%s:%s`".formatted(projectPlugin.getGroupId(),
                    projectPlugin.getArtifactId())));
      }
    };
  }
}
