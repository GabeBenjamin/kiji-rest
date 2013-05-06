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

import static org.kiji.rest.RoutesConstants.INSTANCE_PARAMETER;
import static org.kiji.rest.RoutesConstants.ROWS_PATH;
import static org.kiji.rest.RoutesConstants.TABLE_PARAMETER;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yammer.metrics.annotation.Timed;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import org.kiji.rest.core.KijiRestRow;
import org.kiji.schema.EntityId;
import org.kiji.schema.EntityIdFactory;
import org.kiji.schema.KijiColumnName;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiDataRequestBuilder;
import org.kiji.schema.KijiDataRequestBuilder.ColumnsDef;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiRowScanner;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiTableReader.KijiScannerOptions;
import org.kiji.schema.KijiURI;
import org.kiji.schema.tools.ToolUtils;

/**
 * This REST resource interacts with Kiji tables.
 *
 * This resource is served for requests using the resource identifier:
 * <ul>
 * <li>/v1/instances/&lt;instance&gt/tables/&lt;table&gt;/rows
 * </ul>
 */
@Path(ROWS_PATH)
@Produces(MediaType.APPLICATION_JSON)
public class RowsResource extends AbstractRowResource {

  /**
   * Special constant to denote stream unlimited amount of rows
   * to the client.
   */
  private static final int UNLIMITED_ROWS = -1;

  /**
   * Since we are streaming the rows to the user, we need access to the object mapper
   * used by DropWizard to convert objects to JSON.
   */
  private ObjectMapper mJsonObjectMapper = null;

  /**
   * Default constructor.
   *
   * @param cluster is the KijiURI in which these instances are contained.
   * @param instances is the list of accessible instances.
   * @param jsonObjectMapper is the ObjectMapper used by DropWizard to convert from Java
   *        objects to JSON.
   */
  public RowsResource(KijiURI cluster, Set<KijiURI> instances, ObjectMapper jsonObjectMapper) {
    super(cluster, instances);
    mJsonObjectMapper = jsonObjectMapper;
  }

  /**
   * Class to support streaming KijiRows to the client.
   *
   */
  private class RowStreamer implements StreamingOutput {

    private Iterable<KijiRowData> mScanner = null;
    private final KijiTable mTable;
    private int mNumRows = 0;
    private final List<KijiColumnName> mColsRequested;

    /**
     * Construct a new RowStreamer.
     *
     * @param scanner is the iterator over KijiRowData.
     * @param table the table from which the rows originate.
     * @param numRows is the maximum number of rows to stream.
     * @param columns are the columns requested by the client.
     */
    public RowStreamer(Iterable<KijiRowData> scanner, KijiTable table, int numRows,
        List<KijiColumnName> columns) {
      mScanner = scanner;
      mTable = table;
      mNumRows = numRows;
      mColsRequested = columns;
    }

    /**
     * Performs the actual streaming of the rows.
     *
     * @param os is the OutputStream where the results are written.
     */
    @Override
    public void write(OutputStream os) {
      int numRows = 0;
      Writer writer = new BufferedWriter(new OutputStreamWriter(os, Charset.forName("UTF-8")));
      for (KijiRowData row : mScanner) {
        if (numRows < mNumRows || mNumRows == UNLIMITED_ROWS) {
          try {
            KijiRestRow restRow = getKijiRow(row, mTable.getLayout(), mColsRequested);
            String jsonResult = mJsonObjectMapper.writeValueAsString(restRow);
            // Let's strip out any carriage return + line feeds and replace them with just
            // line feeds. Therefore we can safely delimit individual json messages on the
            // carriage return + line feed for clients to parse properly.
            jsonResult = jsonResult.replaceAll("\r\n", "\n");
            writer.write(jsonResult + "\r\n");
            writer.flush();
          } catch (IOException e) {
            // This most likely means that the client closed the connection
            // and hence close the scanner.
            if (mScanner instanceof KijiRowScanner) {
              try {
                ((KijiRowScanner) mScanner).close();
              } catch (IOException e1) {
                throw new WebApplicationException(e1, Status.INTERNAL_SERVER_ERROR);
              }
            }
            return;
          }
        }
        numRows++;
      }
    }
  }

  /**
   * GETs a list of Kiji rows.
   *
   * @param instance is the instance where the table resides.
   * @param table is the table where the rows from which the rows will be streamed
   * @param jsonEntityId the entity_id of the row to return.
   * @param startHBaseRowKey the hex representation of the starting hbase row key.
   * @param endHBaseRowKey the hex representation of the ending hbase row key.
   * @param limit the maximum number of rows to return.
   * @param columns is a comma separated list of columns (either family or family:qualifier) to
   *        fetch
   * @param maxVersions is the max versions per column to return.
   * @param timeRange is the time range of cells to return (specified by min..max where min/max is
   *        the ms since UNIX epoch. min and max are both optional; however, if something is
   *        specified, at least one of min/max must be present.)
   * @return the Response object containing the rows requested in JSON
   */
  @GET
  @Timed
  // CSOFF: ParameterNumberCheck - There are a bunch of query param options
  public Response getRows(@PathParam(INSTANCE_PARAMETER) String instance,
      @PathParam(TABLE_PARAMETER) String table,
      @QueryParam("eid") String jsonEntityId,
      @QueryParam("start_rk") String startHBaseRowKey,
      @QueryParam("end_rk") String endHBaseRowKey,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @QueryParam("cols") @DefaultValue("*") String columns,
      @QueryParam("versions") @DefaultValue("1") int maxVersions,
      @QueryParam("timerange") String timeRange) {
    // CSON: ParameterNumberCheck - There are a bunch of query param options
    Response rsp = null;
    long[] timeRanges = null;

    KijiTable kijiTable = super.getKijiTable(instance, table);

    if (timeRange != null) {
      timeRanges = getTimestamps(timeRange);
    }

    KijiDataRequestBuilder dataBuilder = KijiDataRequest.builder();
    if (timeRange != null) {
      dataBuilder.withTimeRange(timeRanges[0], timeRanges[1]);
    }

    ColumnsDef colsRequested = dataBuilder.newColumnsDef().withMaxVersions(maxVersions);
    List<KijiColumnName> requestedColumns = addColumnDefs(kijiTable.getLayout(), colsRequested,
        columns);

    if (jsonEntityId != null && (startHBaseRowKey != null || endHBaseRowKey != null)) {
      throw new WebApplicationException(new IllegalArgumentException("Ambiguous request. "
          + "Specified both jsonEntityId and start/end HBase row keys."), Status.BAD_REQUEST);
    }

    // We will honor eid over start/end rk.
    try {
      if (jsonEntityId != null) {
        EntityId eid = ToolUtils.createEntityIdFromUserInputs(jsonEntityId, kijiTable.getLayout());
        KijiRestRow returnRow = super.getKijiRow(kijiTable, eid.getHBaseRowKey(), timeRanges,
            columns, maxVersions);
        rsp = Response.ok(returnRow).build();
      } else {
        EntityIdFactory eidFactory = EntityIdFactory.getFactory(kijiTable.getLayout());
        final KijiScannerOptions scanOptions = new KijiScannerOptions();
        if (startHBaseRowKey != null) {
          EntityId eid = eidFactory.getEntityIdFromHBaseRowKey(Hex.decodeHex(startHBaseRowKey
              .toCharArray()));
          scanOptions.setStartRow(eid);
        }

        if (endHBaseRowKey != null) {
          EntityId eid = eidFactory.getEntityIdFromHBaseRowKey(Hex.decodeHex(endHBaseRowKey
              .toCharArray()));
          scanOptions.setStopRow(eid);
        }

        final KijiTableReader reader = kijiTable.openTableReader();

        final KijiRowScanner scanner = reader.getScanner(dataBuilder.build(), scanOptions);
        rsp = Response.ok(new RowStreamer(scanner, kijiTable, limit, requestedColumns)).build();
      }
    } catch (IOException e) {
      throw new WebApplicationException(e, Status.BAD_REQUEST);
    } catch (DecoderException e) {
      throw new WebApplicationException(e, Status.BAD_REQUEST);
    }

    return rsp;
  }
}