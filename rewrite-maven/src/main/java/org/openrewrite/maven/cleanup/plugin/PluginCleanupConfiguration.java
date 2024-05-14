package org.openrewrite.maven.cleanup.plugin;

import static com.spotify.migrate.parentify.Tags.filterTagById;
import static com.spotify.migrate.parentify.Tags.filterTagWithName;
import static com.spotify.migrate.parentify.Tags.filterTagsWithValues;
import static com.spotify.migrate.parentify.Tags.getLatest;
import static com.spotify.migrate.parentify.cleanup.ConfigurationJson.getConfigurationMap;
import static com.spotify.migrate.parentify.cleanup.SpotifyAddOrUpdateChild.addOrUpdateChild;
import static org.openrewrite.xml.FilterTagChildrenVisitor.filterChildren;

import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.openrewrite.Cursor;
import org.openrewrite.marker.Markers;
import org.openrewrite.maven.tree.Plugin;
import org.openrewrite.xml.tree.Xml.Attribute;
import org.openrewrite.xml.tree.Xml.Attribute.Value.Quote;
import org.openrewrite.xml.tree.Xml.Ident;
import org.openrewrite.xml.tree.Xml.Tag;

/**
 * Cleans up any direct {@code <configuration />} tag from a {@code <plugin />} based on what is
 * already defined in the pom.xml {@code <pluginManagement />}.
 */
@AllArgsConstructor
class PluginCleanupConfiguration {

  private final Cursor cursor;

  Tag cleanup(Tag pluginsTag, final Tag pluginTag, final Plugin projectPlugin,
      final Plugin managedPlugin) {
    if (pluginTag.getChild("configuration").isPresent()) {
      final Tag configurationTag = pluginTag.getChild("configuration").get();
      final var projectPluginConfiguration = getConfigurationMap(projectPlugin.getConfiguration());
      final var managedPluginConfiguration = getConfigurationMap(managedPlugin.getConfiguration());

      pluginsTag = cleanup(pluginsTag, pluginTag, configurationTag, projectPluginConfiguration,
          managedPluginConfiguration);
    }
    return pluginsTag;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @NotNull
  Tag cleanup(Tag parentTag, Tag childTag, final Tag configurationTag,
      final Map<String, Object> projectPluginConfiguration,
      final Map<String, Object> parentPluginConfiguration) {
    if (projectPluginConfiguration == null || parentPluginConfiguration == null) {
      return parentTag;
    }
    if (shouldSkipChanges(configurationTag)) {
      return parentTag;
    }
    final var diff = Maps.difference(projectPluginConfiguration, parentPluginConfiguration);
    if (diff.areEqual()) {
      return filterChildren(parentTag, childTag, filterTagById(configurationTag.getId()));
    }
    childTag = cleanupEntriesInCommon(childTag, configurationTag, diff.entriesInCommon());

    final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
    for (var entry : entriesDiffering.entrySet()) {
      final var entryDiff = entry.getValue();
      final var projectValues = entryDiff.leftValue();
      final var parentValues = entryDiff.rightValue();
      final var tagWithDifferenceName = entry.getKey();
      // All filters and cleanup we run may update tags, but they are not stateful: any changes
      // will generate a new tag, so we often need to try to get the last updated value.
      // The only value that we can guarantee at this point is always updated is
      // `childTag` and `parentTag`
      final var lastUpdatedConfigurationTag = getLatest(childTag, configurationTag.getName());

      if (projectValues instanceof Map projectConfigMap
          && parentValues instanceof Map parentConfigMap) {
        childTag = cleanup(childTag, lastUpdatedConfigurationTag,
            getLatest(lastUpdatedConfigurationTag, tagWithDifferenceName), projectConfigMap,
            parentConfigMap);
        parentTag = addOrUpdateChild(parentTag, childTag, getCursor().getParentOrThrow());
      }
      if (projectValues instanceof List projectList && parentValues instanceof List parentList) {
        childTag = cleanupConfigurationList(childTag, projectList, parentList,
            lastUpdatedConfigurationTag, tagWithDifferenceName);
      }
    }
    return addOrUpdateChild(parentTag, childTag, getCursor().getParentOrThrow());
  }

  private static @NotNull Tag cleanupEntriesInCommon(Tag childTag, final Tag configurationTag,
      final Map<String, Object> entriesInCommon) {
    var updatedConfigurationTag = configurationTag;
    for (final String tagName : entriesInCommon.keySet()) {
      childTag = filterChildren(childTag, updatedConfigurationTag, filterTagWithName(tagName));
      updatedConfigurationTag = getLatest(childTag, configurationTag.getName());
    }
    return childTag;
  }

  private @NotNull Tag cleanupConfigurationList(Tag childTag, final List<String> projectTags,
      final List<String> parentTags, final Tag configurationTag, final String tagName) {
    projectTags.removeAll(parentTags);
    if (projectTags.isEmpty()) {
      // we don't care if parent has more, so we will just remove every entry
      childTag = filterChildren(childTag, configurationTag, filterTagWithName(tagName));
    } else {
      childTag = filterChildren(childTag, configurationTag,
          filterTagsWithValues(tagName, projectTags));
    }
    childTag = CombineChildrenTag.addCombineChildrenTag(childTag, getLatest(childTag, configurationTag.getName()),
        getCursor());
    return childTag;
  }

  private boolean shouldSkipChanges(final Tag configurationTag) {
    return configurationTag.getAttributes().stream()
        .filter(CombineChildrenTag::isCombineChildrenAttribute).map(Attribute::getValueAsString)
        .anyMatch("override"::equalsIgnoreCase);
  }

  private Cursor getCursor() {
    return cursor;
  }

  static class CombineChildrenTag {

    private static final String COMBINE_CHILDREN_ATTR_KEY = "combine.children";

    static @NotNull Tag addCombineChildrenTag(Tag childTag, final Tag configurationTag,
        Cursor cursor) {
      if (doesntHaveCombineChildrenAttribute(configurationTag)) {
        var updatedConfigurationTag = configurationTag;
        var attributes = new ArrayList<>(updatedConfigurationTag.getAttributes());
        final Attribute attribute = createAppendChildrenAttribute();
        attributes.add(attribute);
        updatedConfigurationTag = updatedConfigurationTag.withAttributes(attributes);
        childTag = addOrUpdateChild(childTag, updatedConfigurationTag, cursor);
      }
      return childTag;
    }

    private static boolean doesntHaveCombineChildrenAttribute(final Tag configurationTag) {
      return configurationTag.getAttributes().stream()
          .noneMatch(CombineChildrenTag::isCombineChildrenAttribute);
    }

    static boolean isCombineChildrenAttribute(final Attribute attr) {
      return attr.getKey().getName().equals(COMBINE_CHILDREN_ATTR_KEY);
    }

    static @NotNull Attribute createAppendChildrenAttribute() {
      final Attribute.Value attributeValue = new Attribute.Value(UUID.randomUUID(), "",
          Markers.EMPTY, Quote.Double, "append");
      final Ident attributeKey = new Ident(UUID.randomUUID(), "", Markers.EMPTY,
          COMBINE_CHILDREN_ATTR_KEY);
      return new Attribute(UUID.randomUUID(), "", Markers.EMPTY, attributeKey, "", attributeValue);
    }
  }
}
