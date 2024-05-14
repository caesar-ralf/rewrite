package org.openrewrite.maven.cleanup.plugin;

import static org.openrewrite.xml.AddToTagVisitor.addToTag;
import static org.openrewrite.xml.MapTagChildrenVisitor.mapChildren;
import static org.openrewrite.xml.SemanticallyEqual.areEqual;

import java.util.Optional;
import lombok.AllArgsConstructor;
import org.openrewrite.Cursor;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.xml.tree.Xml.Tag;

/**
 * Rewriting {@link org.openrewrite.xml.AddOrUpdateChild#addOrUpdateChild(Tag, Tag, Cursor)} to
 * handle when there's more than one child with same tag name.
 * <p>
 * This is a quick fix until openrewrite changes the AddOrUpdateChild to the expected behaviour.
 *
 * @param <P>
 */
@AllArgsConstructor
public class SpotifyAddOrUpdateChild<P> extends XmlVisitor<P> {

  Tag scope;
  Tag child;

  @Override
  public Xml visitTag(Tag tag, P p) {
    Tag t = (Tag) super.visitTag(tag, p);
    if (scope.isScope(tag)) {
      Optional<Tag> maybeChild = scope.getChildren(child.getName()).stream()
          .filter(child::isScope).findFirst();
      if (maybeChild.isPresent()) {
        if (areEqual(maybeChild.get(), child)) {
          return t;
        }
        t = mapChildren(t, it -> {
          if (it.isScope(maybeChild.get())) {
            return child.withPrefix(maybeChild.get().getPrefix());
          }
          return it;
        });
        t = autoFormat(t, p, getCursor().getParentOrThrow());
      } else {
        t = addToTag(t, child, getCursor().getParentOrThrow());
      }
    }
    return t;
  }

  /**
   * Add the specified child tag to the parent tag's children. If a tag with the same name as the
   * new child tag already exists within the parent tag's children it is replaced. If no tag with
   * the same name exists, the child tag is added.
   *
   * @param parent       the tag to add 'child' to.
   * @param child        the tag to add to the children of 'parent'.
   * @param parentCursor A cursor pointing one level above 'parent'. Determines the final
   *                     indentation of 'child'.
   * @return 'parent' with 'child' among its direct child tags.
   */
  public static Tag addOrUpdateChild(Tag parent, Tag child, Cursor parentCursor) {
    return addOrUpdateChild(parent, parent, child, parentCursor);
  }

  /**
   * Add the specified child tag to the parent tag's children. If a tag with the same name as the
   * new child tag already exists within the parent tag's children it is replaced. If no tag with
   * the same name exists, the child tag is added.
   *
   * @param parentScope  a tag which contains 'parent' as a direct or transitive child element.
   * @param parent       the tag to add 'child' to.
   * @param child        the tag to add to the children of 'parent'.
   * @param parentCursor A cursor pointing one level above 'parent'. Determines the final
   *                     indentation of 'child'.
   * @return 'parentScope' which somewhere contains 'parent' with 'child' among its direct child
   * tags.
   */
  public static Tag addOrUpdateChild(Tag parentScope, Tag parent, Tag child,
      Cursor parentCursor) {
    //noinspection ConstantConditions
    return (Tag) new SpotifyAddOrUpdateChild<Void>(parent, child).visitNonNull(parentScope,
        null, parentCursor);
  }
}
