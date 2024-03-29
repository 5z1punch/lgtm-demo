/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.entity.EntityHelper;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.orient.OClassNameBuilder;
import org.sonatype.nexus.orient.OIndexNameBuilder;
import org.sonatype.nexus.orient.entity.AttachedEntityId;
import org.sonatype.nexus.orient.entity.IterableEntityAdapter;
import org.sonatype.nexus.repository.browse.BrowsePaths;

import com.google.common.collect.ImmutableMap;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OClass.INDEX_TYPE;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQL;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Lists.newArrayList;

/**
 * {@link BrowseNode} entity-adapter.
 *
 * @since 3.6
 */
@FeatureFlag(name = "nexus.orient.store.config")
@Named
@Singleton
public class BrowseNodeEntityAdapter
    extends IterableEntityAdapter<BrowseNode>
{
  private static final String DB_CLASS = new OClassNameBuilder().type("browse_node").build();

  public static final String P_REPOSITORY_NAME = "repository_name";

  public static final String P_FORMAT = "format";

  public static final String P_PATH = "path";

  public static final String P_PARENT_PATH = "parent_path";

  public static final String P_NAME = "name";

  public static final String P_COMPONENT_ID = "component_id";

  public static final String P_ASSET_ID = "asset_id";

  public static final String AUTHZ_REPOSITORY_NAME = "authz_repository_name";

  private static final String BASE_PATH = "base_path";

  private static final String LIMIT = "limit";

  private static final String I_REPOSITORY_NAME_PARENT_PATH_NAME = new OIndexNameBuilder()
      .type(DB_CLASS)
      .property(P_REPOSITORY_NAME)
      .property(P_PARENT_PATH)
      .property(P_NAME)
      .build();

  private static final String I_COMPONENT_ID = new OIndexNameBuilder()
      .type(DB_CLASS)
      .property(P_COMPONENT_ID)
      .build();

  private static final String I_ASSET_ID = new OIndexNameBuilder()
      .type(DB_CLASS)
      .property(P_ASSET_ID)
      .build();

  private static final String FIND_BY_PARENT_PATH = String.format(
      "select expand(rid) from index:%s where key=[:%s,:%s,:%s] limit 1",
      I_REPOSITORY_NAME_PARENT_PATH_NAME, P_REPOSITORY_NAME, P_PARENT_PATH, P_NAME);

  private static final String FIND_CHILDREN = String.format(
      "select from %s where (%s=:%s and %s=:%s)",
      DB_CLASS, P_REPOSITORY_NAME, P_REPOSITORY_NAME, P_PARENT_PATH, BASE_PATH);

  private static final String CHILD_COUNT = String.format(
      "select rid from `index:%s` where key=[:%s, :%s] limit :%s",
      I_REPOSITORY_NAME_PARENT_PATH_NAME, P_REPOSITORY_NAME, BASE_PATH, LIMIT);

  private static final String FIND_BY_COMPONENT = String.format(
      "select from %s where %s=:%s",
      DB_CLASS, P_COMPONENT_ID, P_COMPONENT_ID);

  private static final String FIND_BY_ASSET = String.format(
      "select from %s where %s=:%s limit 1",
      DB_CLASS, P_ASSET_ID, P_ASSET_ID);

  private static final String DELETE_BY_REPOSITORY = String.format(
      "delete from %s where %s=:%s limit :limit",
      DB_CLASS, P_REPOSITORY_NAME, P_REPOSITORY_NAME);

  private final ComponentEntityAdapter componentEntityAdapter;

  private final AssetEntityAdapter assetEntityAdapter;

  @Inject
  public BrowseNodeEntityAdapter(final ComponentEntityAdapter componentEntityAdapter,
                                 final AssetEntityAdapter assetEntityAdapter)
  {
    super(DB_CLASS);
    this.assetEntityAdapter = checkNotNull(assetEntityAdapter);
    this.componentEntityAdapter = checkNotNull(componentEntityAdapter);
  }

  @Override
  protected void defineType(final OClass type) {
    type.createProperty(P_REPOSITORY_NAME, OType.STRING).setMandatory(true).setNotNull(true);
    type.createProperty(P_FORMAT, OType.STRING).setMandatory(true).setNotNull(true);
    type.createProperty(P_PATH, OType.STRING).setMandatory(true).setNotNull(true);
    type.createProperty(P_PARENT_PATH, OType.STRING).setMandatory(true).setNotNull(true);
    type.createProperty(P_NAME, OType.STRING).setMandatory(true).setNotNull(true);
    type.createProperty(P_COMPONENT_ID, OType.LINK, componentEntityAdapter.getSchemaType());
    type.createProperty(P_ASSET_ID, OType.LINK, assetEntityAdapter.getSchemaType());
  }

  @Override
  protected void defineType(final ODatabaseDocumentTx db, final OClass type) {
    defineType(type);

    // primary index that guarantees path uniqueness for nodes in a given repository
    type.createIndex(I_REPOSITORY_NAME_PARENT_PATH_NAME, INDEX_TYPE.UNIQUE, P_REPOSITORY_NAME, P_PARENT_PATH, P_NAME);

    // save space and ignore nulls because we'll never query on a null component/asset id
    ODocument ignoreNullValues = db.newInstance().field("ignoreNullValues", true);
    type.createIndex(I_COMPONENT_ID, INDEX_TYPE.NOTUNIQUE.name(), null, ignoreNullValues, new String[] { P_COMPONENT_ID });
    type.createIndex(I_ASSET_ID, INDEX_TYPE.UNIQUE.name(), null, ignoreNullValues, new String[] { P_ASSET_ID });
  }

  @Override
  protected BrowseNode newEntity() {
    return new BrowseNode();
  }

  @Override
  protected void readFields(final ODocument document, final BrowseNode entity) {
    String repositoryName = document.field(P_REPOSITORY_NAME, OType.STRING);
    String format = document.field(P_FORMAT, OType.STRING);
    String path = document.field(P_PATH, OType.STRING);
    String parentPath = document.field(P_PARENT_PATH, OType.STRING);
    String name = document.field(P_NAME, OType.STRING);

    entity.setRepositoryName(repositoryName);
    entity.setFormat(format);
    entity.setPath(path);
    entity.setParentPath(parentPath);
    entity.setName(name);

    ORID componentId = document.field(P_COMPONENT_ID, ORID.class);
    if (componentId != null) {
      entity.setComponentId(new AttachedEntityId(componentEntityAdapter, componentId));
    }

    ORID assetId = document.field(P_ASSET_ID, ORID.class);
    if (assetId != null) {
      entity.setAssetId(new AttachedEntityId(assetEntityAdapter, assetId));
    }
  }

  @Override
  protected void writeFields(final ODocument document, final BrowseNode entity) throws Exception {
    document.field(P_REPOSITORY_NAME, entity.getRepositoryName());
    document.field(P_FORMAT, entity.getFormat());
    document.field(P_PATH, entity.getPath());
    document.field(P_PARENT_PATH, entity.getParentPath());
    document.field(P_NAME, entity.getName());

    if (entity.getComponentId() != null) {
      document.field(P_COMPONENT_ID, componentEntityAdapter.recordIdentity(entity.getComponentId()));
    }

    if (entity.getAssetId() != null) {
      document.field(P_ASSET_ID, assetEntityAdapter.recordIdentity(entity.getAssetId()));
    }
  }

  /**
   * Associates a {@link BrowseNode} with the given {@link Component}.
   */
  public void createComponentNode(final ODatabaseDocumentTx db,
                                  final String repositoryName,
                                  final String format,
                                  final List<BrowsePaths> paths,
                                  final Component component)
  {
    //create any parent folder nodes for this component if not already existing
    maybeCreateParentNodes(db, repositoryName, format, paths.subList(0, paths.size() - 1));

    //now create the component node
    BrowseNode node = newNode(repositoryName, format, paths);
    ODocument document = findNodeRecord(db, node);
    if (document == null) {
      // complete the new entity before persisting
      node.setComponentId(EntityHelper.id(component));
      addEntity(db, node);
    }
    else {
      ORID oldComponentId = document.field(P_COMPONENT_ID, ORID.class);
      ORID newComponentId = componentEntityAdapter.recordIdentity(component);
      if (oldComponentId == null) {
        // shortcut: merge new information directly into existing record
        document.field(P_COMPONENT_ID, newComponentId);
        document.save();
      }
      else if (!oldComponentId.equals(newComponentId)) {
        // retry in case this is due to an out-of-order delete event
        throw new BrowseNodeCollisionException("Node already has a component");
      }
    }
  }

  /**
   * Associates a {@link BrowseNode} with the given {@link Asset}.
   */
  public void createAssetNode(final ODatabaseDocumentTx db,
                              final String repositoryName,
                              final String format,
                              final List<BrowsePaths> paths,
                              final Asset asset)
  {
    //create any parent folder nodes for this asset if not already existing
    maybeCreateParentNodes(db, repositoryName, format, paths.subList(0, paths.size() - 1));

    //now create the asset node
    BrowseNode node = newNode(repositoryName, format, paths);
    ODocument document = findNodeRecord(db, node);
    if (document == null) {
      // complete the new entity before persisting
      node.setAssetId(EntityHelper.id(asset));
      addEntity(db, node);
    }
    else {
      ORID oldAssetId = document.field(P_ASSET_ID, ORID.class);
      ORID newAssetId = assetEntityAdapter.recordIdentity(asset);
      if (oldAssetId == null) {
        // shortcut: merge new information directly into existing record
        document.field(P_ASSET_ID, newAssetId);
        String path = document.field(P_PATH, OType.STRING);

        //if this node is now an asset, we don't want a trailing slash
        if (!asset.name().endsWith("/") && path.endsWith("/")) {
          path = path.substring(0, path.length() - 1);
          document.field(P_PATH, path);
        }
        document.save();
      }
      else if (!oldAssetId.equals(newAssetId)) {
        // retry in case this is due to an out-of-order delete event
        throw new BrowseNodeCollisionException("Node already has an asset");
      }
    }
  }

  /**
   * Iterate over the list of path strings, and create a browse node for each one if not already there.
   */
  private void maybeCreateParentNodes(final ODatabaseDocumentTx db,
                                      final String repositoryName,
                                      final String format,
                                      final List<BrowsePaths> paths)
  {
    for (int i = paths.size() ; i > 0 ; i--) {
      BrowseNode parentNode = newNode(repositoryName, format, paths.subList(0, i));
      if (!parentNode.getPath().endsWith("/")) {
        parentNode.setPath(parentNode.getPath() + "/");
      }
      ODocument document = findNodeRecord(db, parentNode);
      if (document == null) {
        addEntity(db, parentNode);
      }
      else {
        //if the parent exists, but doesn't have proper folder path (ending with "/") change it
        //this would typically only happen with nested assets
        if (!document.field(P_PATH).toString().endsWith("/")) {
          document.field(P_PATH, document.field(P_PATH) + "/");
          document.save();
        }
        break;
      }
    }
  }

  /**
   * Creates a basic {@link BrowseNode} for the given repository and path.
   */
  private static BrowseNode newNode(final String repositoryName, final String format, final List<BrowsePaths> paths) {
    BrowseNode node = new BrowseNode();
    node.setRepositoryName(repositoryName);
    node.setFormat(format);
    node.setPaths(paths);
    return node;
  }

  /**
   * Returns the {@link BrowseNode} with the same coordinates as the sample node; {@code null} if no such node exists.
   */
  @Nullable
  private static ODocument findNodeRecord(final ODatabaseDocumentTx db, BrowseNode node) {
    return getFirst(
        db.command(new OCommandSQL(FIND_BY_PARENT_PATH)).execute(
            ImmutableMap.of(
                P_REPOSITORY_NAME, node.getRepositoryName(),
                P_PARENT_PATH, node.getParentPath(),
                P_NAME, node.getName())),
        null);
  }

  /**
   * Removes any {@link BrowseNode}s associated with the given component id.
   */
  public void deleteComponentNode(final ODatabaseDocumentTx db, final EntityId componentId) {
    // some formats have the same component appearing on different branches of the tree
    Iterable<ODocument> documents =
        db.command(new OCommandSQL(FIND_BY_COMPONENT)).execute(
            ImmutableMap.of(P_COMPONENT_ID, recordIdentity(componentId)));

    documents.forEach(document -> {
      if (document.containsField(P_ASSET_ID)) {
        // asset still exists, just remove component details
        document.removeField(P_COMPONENT_ID);
        document.save();
      }
      else {
        maybeDeleteParents(db, document.field(P_REPOSITORY_NAME), document.field(P_PARENT_PATH));
        document.delete();
      }
    });
  }

  /**
   * Removes the {@link BrowseNode} associated with the given asset id.
   */
  public void deleteAssetNode(final ODatabaseDocumentTx db, final EntityId assetId) {
    // a given asset will only appear once in the tree
    ODocument document = getFirst(
        db.command(new OCommandSQL(FIND_BY_ASSET)).execute(
            ImmutableMap.of(P_ASSET_ID, recordIdentity(assetId))), null);

    if (document != null) {
      if (document.containsField(P_COMPONENT_ID)) {
        // component still exists, just remove asset details
        document.removeField(P_ASSET_ID);
        document.save();
      }
      else {
        maybeDeleteParents(db, document.field(P_REPOSITORY_NAME), document.field(P_PARENT_PATH));
        document.delete();
      }
    }
  }

  /**
   * Removes a number of {@link BrowseNode}s belonging to the given repository (so we can batch the deletes).
   */
  public int deleteByRepository(final ODatabaseDocumentTx db, final String repositoryName, final int limit) {
    return db.command(new OCommandSQL(DELETE_BY_REPOSITORY)).execute(
        ImmutableMap.of(P_REPOSITORY_NAME, repositoryName, "limit", limit));
  }

  /**
   * Returns the {@link BrowseNode}s directly visible under the given path, according to the given asset filter.
   */
  public List<BrowseNode> getByPath(final ODatabaseDocumentTx db,
                                    final String repositoryName,
                                    final List<String> path,
                                    final int maxNodes,
                                    final String assetFilter,
                                    final Map<String, Object> filterParameters)
  {
    Map<String, Object> parameters = new HashMap<>(filterParameters);
    parameters.put(P_REPOSITORY_NAME, repositoryName);

    // STEP 1: make a note of any direct child nodes that are visible
    OCommandSQL sql = buildQuery(FIND_CHILDREN, assetFilter, maxNodes);

    String basePath = joinPath(path);
    parameters.put(BASE_PATH, basePath);

    List<BrowseNode> children = newArrayList(transform(db.command(sql).execute(parameters)));

    children.forEach(child -> {
      // STEP 2: check if the child has any children of its own, if not, it's a leaf
      if (childCountEqualTo(db, repositoryName, child.getParentPath() + child.getName() + "/", 0)) {
        child.setLeaf(true);
      }
    });

    return children;
  }

  /**
   * remove any parent nodes that only contain 1 child, and if not an asset/component node of course
   */
  private void maybeDeleteParents(final ODatabaseDocumentTx db, final String repositoryName, final String parentPath) {
    //count of 1 meaning the node we are currently deleting
    if (!"/".equals(parentPath) && childCountEqualTo(db, repositoryName, parentPath, 1)) {
      ODocument parent = getFirst(db.command(new OCommandSQL(FIND_BY_PARENT_PATH)).execute(ImmutableMap
          .of(P_REPOSITORY_NAME, repositoryName, P_PARENT_PATH, previousParentPath(parentPath), P_NAME,
              previousParentName(parentPath))), null);

      if (parent != null && parent.field(P_COMPONENT_ID) == null && parent.field(P_ASSET_ID) == null) {
        maybeDeleteParents(db, repositoryName, parent.field(P_PARENT_PATH));
        parent.delete();
      }
    }
  }

  /**
   * take a string path and return the string at the previous level, i.e. "/foo/bar/com/" -> "/foo/bar/"
   */
  private String previousParentPath(String parentPath) {
    //parentPath always ends with slash, pull it out for this check
    String withoutSlash = parentPath.substring(0, parentPath.length() - 1);
    //make sure to include the slash
    return withoutSlash.substring(0, withoutSlash.lastIndexOf('/') + 1);
  }

  /**
   * take a string path and return the string of the last segment, i.e. "/foo/bar/com/" -> "com"
   */
  private String previousParentName(String parentPath) {
    //parentPath always ends with slash, pull it out for this check
    String withoutSlash = parentPath.substring(0, parentPath.length() - 1);

    return withoutSlash.substring(withoutSlash.lastIndexOf('/') + 1);
  }

  /**
   * Joins segments into a path which always starts and ends with a single slash.
   */
  private static String joinPath(final List<String> path) {
    StringBuilder buf = new StringBuilder("/");
    path.forEach(s -> buf.append(s).append('/'));
    return buf.toString();
  }

  /**
   * Builds a visible node query from the primary select clause, optional asset filter, and limit.
   *
   * Optionally include nodes which don't have assets (regardless of the filter) to allow their
   * component details to be used in the final listing when they overlap with visible subtrees.
   */
  private OCommandSQL buildQuery(final String select,
                                        final String assetFilter,
                                        final int limit)
  {
    StringBuilder buf = new StringBuilder(select);

    if (!assetFilter.isEmpty()) {
      buf.append(" and (").append(assetFilter).append(')');
    }

    buf.append(" limit ").append(limit);

    return new OCommandSQL(buf.toString());
  }

  /**
   * Enables deconfliction of browse nodes.
   */
  @Override
  public boolean resolveConflicts() {
    return true;
  }

  private boolean childCountEqualTo(final ODatabaseDocumentTx db,
                                    final String repositoryName, final String basePath, final long expected)
  {
    List<ODocument> docs = db.command(new OCommandSQL(CHILD_COUNT)).execute(
        ImmutableMap.of(P_REPOSITORY_NAME, repositoryName, BASE_PATH, basePath, LIMIT, expected + 1));
    return docs.size() == expected;
  }
}
