/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.rest.resources;

import static org.kiji.rest.resources.ResourceConstants.API_ENTRY_POINT;
import static org.kiji.rest.resources.ResourceConstants.INSTANCES;
import static org.kiji.rest.resources.ResourceConstants.TABLES;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.google.common.collect.Maps;
import com.yammer.metrics.annotation.Timed;

import org.kiji.schema.Kiji;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiURI;
import org.kiji.schema.KijiURI.KijiURIBuilder;

/**
 * This REST resource interacts with Kiji tables..
 * This resource is served whenever requests are made using the following
 * resource identifiers: /v1/instances/&lt;instance&gt/tables,
 * /v1/instances/&lt;instance&gt/tables/&lt;singleton&gt;,
 * /v1/instances/&lt;instance&gt/tables/&lt;table&gt;.
 */
@Path(API_ENTRY_POINT + INSTANCES + "{instance}/" + TABLES)
@Produces(MediaType.APPLICATION_JSON)
public class TableResource {
  private final KijiURI mCluster;
  private final List<KijiURI> mInstances;

  /**
   * Construct the InstanceResource with the cluster URI.
   *
   * @param cluster KijiURI in which these tables are contained.
   * @param instances list of KijiURIs denoting instances.
   */
  public TableResource(KijiURI cluster, List<KijiURI> instances) {
    super();
    mCluster = cluster;
    mInstances = instances;
  }

  /**
   * Lists the tables on the instance.
   * Called when the terminal resource element is not identified.
   *
   * @param instance to list the contained tables.
   * @return a list of tables on the instance.
   * @throws IOException if the instance is unavailable.
   */
  @GET
  @Timed
  public Map<String, Object> list(@PathParam("instance") String instance) throws IOException {
    final KijiURI kijiURI = KijiURI.newBuilder(mCluster).withInstanceName(instance).build();
    if (!mInstances.contains(kijiURI)) {
      throw new IOException("Instance unavailable");
    }
    final Map<String, Object> outputMap = Maps.newTreeMap();
    final Kiji kiji = Kiji.Factory.open(kijiURI);
    outputMap.put("parent_kiji_uri", kijiURI.toOrderedString());
    outputMap.put("tables", kiji.getTableNames());
    kiji.release();
    return outputMap;
  }

  /**
   * Called when the terminal resource element is the table name.
   * @param instance in which the table resides.
   * @param table to be inspected.
   * @return a message containing a list of available sub-resources.
   * @throws IOException if the instance or table is unavailable.
   */
  @Path("{table}")
  @GET
  @Timed
  public Map<String, Object> table(@PathParam("instance") String instance,
      @PathParam("table") String table) throws IOException {
    final KijiURIBuilder kijiURIBuilder = KijiURI.newBuilder(mCluster).withInstanceName(instance);
    if (!mInstances.contains(kijiURIBuilder.build())) {
      throw new IOException("Instance unavailable");
    }
    final KijiURI kijiURI = kijiURIBuilder.withTableName(table).build();

    // TODO Is there a better way to accomplish this check?
    // Checks is the table can be successfully opened and release.
    final Kiji kiji = Kiji.Factory.open(kijiURI);
    final KijiTable checkTable = kiji.openTable(kijiURI.getTable());
    checkTable.release();
    kiji.release();

    Map<String, Object> namespace = Maps.newHashMap();
    namespace.put("table_name", kijiURI.getTable());
    namespace.put("kiji_uri", kijiURI.toOrderedString());
    return namespace;
  }

  /**
   * Called when the terminal resource element is the 'layout' of the table.
   * @param instance in which the table resides.
   * @param table to return the layout for.
   * @return a JSON table layout.
   * @throws IOException if the instance is unavailable.
   */
  @Path("{table}/layout")
  @GET
  @Timed
  public String layout(@PathParam("instance") String instance,
      @PathParam("table") String table) throws IOException {
    final KijiURIBuilder kijiURIBuilder = KijiURI.newBuilder(mCluster).withInstanceName(instance);
    if (!mInstances.contains(kijiURIBuilder.build())) {
      throw new IOException("Instance unavailable");
    }
    final KijiURI kijiURI = kijiURIBuilder.withTableName(table).build();
    final Kiji kiji = Kiji.Factory.open(kijiURI);
    final String layout = kiji
        .getMetaTable()
        .getTableLayout(kijiURI.getTable())
        .getDesc()
        .toString();
    kiji.release();
    return layout;
  }
}

