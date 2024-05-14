package org.openrewrite.maven.cleanup.plugin;

import static com.spotify.migrate.parentify.Tags.filterTagWithName;
import static com.spotify.migrate.parentify.cleanup.SpotifyAddOrUpdateChild.addOrUpdateChild;
import static org.openrewrite.xml.FilterTagChildrenVisitor.filterChildren;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.NotNull;
import org.openrewrite.Cursor;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.Plugin;
import org.openrewrite.xml.tree.Xml.Tag;

/**
 * Cleans up any {@code <dependencies />} tag from a {@code <plugin />} based on what is already
 * defined in the pom.xml {@code <pluginManagement />}.
 */
@AllArgsConstructor
class PluginCleanupDependencies {

  private final Cursor cursor;

  Tag cleanup(Tag pluginsTag, Tag pluginTag, final Plugin projectPlugin,
      final Plugin managedPlugin) {
    if (pluginTag.getChild("dependencies").isEmpty()) {
      return pluginsTag;
    }
    final var projectDependencies = projectPlugin.getDependencies().stream().map(Dependency::getGav)
        .collect(Collectors.toSet());
    final var managedDependencies = managedPlugin.getDependencies().stream().map(Dependency::getGav)
        .collect(Collectors.toSet());
    if (Objects.deepEquals(projectDependencies, managedDependencies)) {
      return filterChildren(pluginsTag, pluginTag, filterTagWithName("dependencies"));
    }
    var differentDependenciesInProject = new HashSet<>(projectDependencies);
    differentDependenciesInProject.removeAll(managedDependencies);
    if (differentDependenciesInProject.isEmpty()) {
      return filterChildren(pluginsTag, pluginTag, filterTagWithName("dependencies"));
    }
    if (pluginTag.getChild("dependencies").isPresent()) {
      var dependenciesTag = pluginTag.getChild("dependencies").get();
      final var tagDependencies = dependenciesTag.getChildren().stream()
          .map(this::toGroupArtifactVersion).collect(Collectors.toSet());
      if (!Objects.deepEquals(tagDependencies, differentDependenciesInProject)) {
        pluginTag = filterChildren(pluginTag, dependenciesTag, t -> {
          if (t instanceof Tag dependencyTag) {
            final var gav = toGroupArtifactVersion(dependencyTag);
            final var dependencyInProject = findDependency(differentDependenciesInProject, gav);
            if (dependencyInProject.isPresent()) {
              final var maybeManagedDependency = findDependency(managedDependencies, gav);
              // check if it has one with same id in managed, just the version  is different
              if (maybeManagedDependency.isPresent()) {
                final var managedDependency = maybeManagedDependency.get();
                if (managedDependency.getVersion() != null && gav.getVersion() != null) {
                  final var managedVersion = new ComparableVersion(managedDependency.getVersion());
                  final var projectVersion = new ComparableVersion(gav.getVersion());
                  return projectVersion.compareTo(managedVersion) > 0;
                }
              } else {
                // it's only present at the project
                return true;
              }
            }
          }
          return false;
        });
        return addOrUpdateChild(pluginsTag, pluginTag, getCursor().getParentOrThrow());
      }
    }
    return pluginsTag;
  }

  private static @NotNull Optional<GroupArtifactVersion> findDependency(
      final Collection<GroupArtifactVersion> differentDependenciesInProject,
      final GroupArtifactVersion gav) {
    return differentDependenciesInProject.stream().filter(
        d -> d.getGroupId() != null && d.getGroupId().equals(gav.getGroupId()) && d.getArtifactId()
            .equals(gav.getArtifactId())).findFirst();
  }

  private GroupArtifactVersion toGroupArtifactVersion(final Tag dependencyTag) {
    final var groupId = dependencyTag.getChildValue("groupId")
        .orElseThrow(() -> new IllegalStateException("dependency should always have groupId tag!"));
    final var artifactId = dependencyTag.getChildValue("artifactId").orElseThrow(
        () -> new IllegalStateException("dependency should always have artifactId tag!"));
    final var version = dependencyTag.getChildValue("version").orElse(null);
    return new GroupArtifactVersion(groupId, artifactId, version);
  }

  private Cursor getCursor() {
    return this.cursor;
  }
}
