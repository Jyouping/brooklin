package com.linkedin.datastream.connectors.mysql.or;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.code.or.binlog.BinlogEventListener;
import com.google.code.or.binlog.BinlogEventV4;
import com.google.code.or.binlog.BinlogEventV4Header;
import com.google.code.or.binlog.impl.event.AbstractRowEvent;
import com.google.code.or.binlog.impl.event.DeleteRowsEvent;
import com.google.code.or.binlog.impl.event.DeleteRowsEventV2;
import com.google.code.or.binlog.impl.event.FormatDescriptionEvent;
import com.google.code.or.binlog.impl.event.GtidEvent;
import com.google.code.or.binlog.impl.event.QueryEvent;
import com.google.code.or.binlog.impl.event.RotateEvent;
import com.google.code.or.binlog.impl.event.StopEvent;
import com.google.code.or.binlog.impl.event.TableMapEvent;
import com.google.code.or.binlog.impl.event.UpdateRowsEvent;
import com.google.code.or.binlog.impl.event.UpdateRowsEventV2;
import com.google.code.or.binlog.impl.event.WriteRowsEvent;
import com.google.code.or.binlog.impl.event.WriteRowsEventV2;
import com.google.code.or.binlog.impl.event.XidEvent;
import com.google.code.or.common.glossary.Column;
import com.google.code.or.common.glossary.Pair;
import com.google.code.or.common.glossary.Row;

import com.linkedin.datastream.common.DatastreamEvent;
import com.linkedin.datastream.common.DatastreamEventMetadata;
import com.linkedin.datastream.common.DatastreamRuntimeException;
import com.linkedin.datastream.common.JsonUtils;
import com.linkedin.datastream.connectors.mysql.MysqlCheckpoint;
import com.linkedin.datastream.server.DatastreamEventProducer;
import com.linkedin.datastream.server.DatastreamProducerRecordBuilder;
import com.linkedin.datastream.server.DatastreamTask;


public class MysqlBinlogEventListener implements BinlogEventListener {
  private static final Logger LOG = LoggerFactory.getLogger(MysqlBinlogEventListener.class);

  // helper for debugging and logging
  private final Map<Integer, String> _eventTypeNames = new HashMap<>();
  private static final String DEFAULT_SOURCE_ID = "None";

  private final DatastreamEventProducer _producer;
  private final DatastreamTask _datastreamTask;
  private final TableInfoProvider _tableInfoProvider;
  private long _mostRecentSeenEventPosition;
  private long _timestampLastSeenEvent;
  private boolean _isBeginTxnSeen;
  private String _sourceId;
  private long _scn;
  private String _currFileName;

  /** Track current table name as reported in bin logs. */
  private HashMap<Long, String> _currDBTableNamesInTransaction = new HashMap<>();

  private List<DatastreamEvent> _eventsInTransaction = new ArrayList<>();

  /** Cache of the column metadata for all the tables that are processed by this event listener **/
  HashMap<String, List<ColumnInfo>> _columnMetadataCache = new HashMap<>();

  public MysqlBinlogEventListener(DatastreamTask datastreamTask, TableInfoProvider tableInfoProvider) {
    initEventTypeNames();

    _datastreamTask = datastreamTask;
    _producer = _datastreamTask.getEventProducer();
    _tableInfoProvider = tableInfoProvider;
  }

  private void initEventTypeNames() {
    _eventTypeNames.put(0, "UNKNOWN_EVENT");
    _eventTypeNames.put(1, "START_EVENT_V3");
    _eventTypeNames.put(2, "QUERY_EVENT");
    _eventTypeNames.put(3, "STOP_EVENT");
    _eventTypeNames.put(4, "ROTATE_EVENT");
    _eventTypeNames.put(5, "INTVAR_EVENT");
    _eventTypeNames.put(6, "LOAD_EVENT");
    _eventTypeNames.put(7, "SLAVE_EVENT");
    _eventTypeNames.put(8, "CREATE_FILE_EVENT");
    _eventTypeNames.put(9, "APPEND_BLOCK_EVENT");
    _eventTypeNames.put(10, "EXEC_LOAD_EVENT");
    _eventTypeNames.put(11, "DELETE_FILE_EVENT");
    _eventTypeNames.put(12, "NEW_LOAD_EVENT");
    _eventTypeNames.put(13, "RAND_EVENT");
    _eventTypeNames.put(14, "USER_VAR_EVENT");
    _eventTypeNames.put(15, "FORMAT_DESCRIPTION_EVENT");
    _eventTypeNames.put(16, "XID_EVENT");
    _eventTypeNames.put(17, "BEGIN_LOAD_QUERY_EVENT");
    _eventTypeNames.put(18, "EXECUTE_LOAD_QUERY_EVENT");
    _eventTypeNames.put(19, "TABLE_MAP_EVENT");
    _eventTypeNames.put(20, "PRE_GA_WRITE_ROWS_EVENT");
    _eventTypeNames.put(21, "PRE_GA_UPDATE_ROWS_EVENT");
    _eventTypeNames.put(22, "PRE_GA_DELETE_ROWS_EVENT");
    _eventTypeNames.put(23, "WRITE_ROWS_EVENT");
    _eventTypeNames.put(24, "UPDATE_ROWS_EVENT");
    _eventTypeNames.put(25, "DELETE_ROWS_EVENT");
    _eventTypeNames.put(26, "INCIDENT_EVENT");
    _eventTypeNames.put(27, "HEARTBEAT_LOG_EVENT");
  }

  @Override
  public void onEvents(BinlogEventV4 event) {
    if (event == null) {
      LOG.error("Received null event");
      return;
    }

    LOG.info("Event Type = " + event.getHeader().getEventType() + " - " + _eventTypeNames.get(
        event.getHeader().getEventType()));

    LOG.trace("e: " + event);
    _mostRecentSeenEventPosition = event.getHeader().getPosition();
    _timestampLastSeenEvent = System.currentTimeMillis();

    if (event instanceof RotateEvent) {
      handleRotateEvent(event);
      return;
    }

    if (isEventIgnorable(event)) {
      return;
    }

    if (event instanceof GtidEvent) {
      _sourceId = getSourceId(event);
      _scn = getScn(event);
      LOG.info("Start of the transaction " + _scn);
      return;
    }

    if (isEndTransactionEvent(event)) {
      endTransaction(event);
      return;
    }

    if (isRollbackEvent(event)) {
      rollbackTransaction(event);
      return;
    }

    if (event instanceof TableMapEvent) {
      TableMapEvent tme = (TableMapEvent) event;
      processTableMapEvent(tme);
      return;
    } else if (event instanceof AbstractRowEvent) {
      processAbstractRowEvent((AbstractRowEvent) event);
      return;
    } else {
      LOG.warn("Skipping !! Unknown OR event e: " + event);
      return;
    }
  }

  private void processAbstractRowEvent(AbstractRowEvent event) {
    if (event instanceof WriteRowsEvent) {
      WriteRowsEvent wre = (WriteRowsEvent) event;
      processBinlogDataEvent(wre.getHeader(), wre.getTableId(), wre.getRows(), WriteRowsEvent.EVENT_TYPE);
    } else if (event instanceof WriteRowsEventV2) {
      WriteRowsEventV2 wre = (WriteRowsEventV2) event;
      processBinlogDataEvent(wre.getHeader(), wre.getTableId(), wre.getRows(), WriteRowsEventV2.EVENT_TYPE);
    } else if (event instanceof UpdateRowsEvent) {
      UpdateRowsEvent ure = (UpdateRowsEvent) event;
      processBinlogDataEvent(ure.getHeader(), ure.getTableId(),
          ure.getRows().stream().map(Pair::getAfter).collect(Collectors.toList()), UpdateRowsEvent.EVENT_TYPE);
    } else if (event instanceof UpdateRowsEventV2) {
      UpdateRowsEventV2 ure = (UpdateRowsEventV2) event;
      processBinlogDataEvent(ure.getHeader(), ure.getTableId(),
          ure.getRows().stream().map(Pair::getAfter).collect(Collectors.toList()), UpdateRowsEventV2.EVENT_TYPE);
    } else if (event instanceof DeleteRowsEventV2) {
      DeleteRowsEventV2 dre = (DeleteRowsEventV2) event;
      processBinlogDataEvent(dre.getHeader(), dre.getTableId(), dre.getRows(), DeleteRowsEvent.EVENT_TYPE);
    } else if (event instanceof DeleteRowsEvent) {
      DeleteRowsEvent dre = (DeleteRowsEvent) event;
      processBinlogDataEvent(dre.getHeader(), dre.getTableId(), dre.getRows(), DeleteRowsEventV2.EVENT_TYPE);
    } else {
      LOG.warn("Skipping !! Unknown OR event e: " + event);
      return;
    }
  }

  /*
   * Process binlog data events (Insert, Update, Delete)
   */
  private void processBinlogDataEvent(BinlogEventV4Header binlogEventHeader, long tableId, List<Row> rows,
      final int binlogEventType) {
    String currFullDBTableName = _currDBTableNamesInTransaction.get(tableId);

    if (currFullDBTableName == null || currFullDBTableName.isEmpty()) {
      String errMsg = "Got a call to processBinlogDataEvent with currFullDBTablename = " + currFullDBTableName
          + " binlogEventHeader = " + binlogEventHeader + " _currFileName = " + _currFileName + " position = "
          + (int) binlogEventHeader.getPosition();
      LOG.error(errMsg);
      return;
    }

    String[] tableNameParts = currFullDBTableName.split("[.]");
    LOG.debug(
        "File Number: " + _currFileName + ", Position: " + (int) binlogEventHeader.getPosition() + ", SCN =" + _scn);

    List<ColumnInfo> columnMetadata = _tableInfoProvider.getColumnList(tableNameParts[0], tableNameParts[1]);

    for (int index = 0; index < rows.size(); index++) {
      Row row = rows.get(index);
      HashMap<String, String> keyValues = new HashMap<>();
      HashMap<String, String> rowValues = new HashMap<>();
      List<Column> columns = row.getColumns();
      for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
        ColumnInfo columnInfo = columnMetadata.get(columnIndex);
        rowValues.put(columnInfo.getColumnName(), columns.get(columnIndex).toString());
        if (columnInfo.isKey()) {
          keyValues.put(columnInfo.getColumnName(), columns.get(columnIndex).toString());
        }
      }

      _eventsInTransaction.add(buildDatastreamEvent(binlogEventType, String.format("%s:%s", _sourceId, _scn),
          binlogEventHeader.getTimestamp(), tableNameParts[0], tableNameParts[1], JsonUtils.toJson(keyValues),
          JsonUtils.toJson(rowValues)));
    }
  }

  private DatastreamEvent buildDatastreamEvent(int binlogEventType, String gtid, long eventTimestamp, String dbName,
      String tableName, String key, String value) {
    Map<CharSequence, CharSequence> metadata = new HashMap<>();
    metadata.put(DatastreamEventMetadata.OPCODE, getOpCode(binlogEventType).toString());
    metadata.put("Gtid", gtid);
    metadata.put(DatastreamEventMetadata.EVENT_TIMESTAMP, String.valueOf(eventTimestamp));
    metadata.put(DatastreamEventMetadata.DATABASE, dbName);
    metadata.put(DatastreamEventMetadata.TABLE, tableName);

    DatastreamEvent datastreamEvent = new DatastreamEvent();
    datastreamEvent.payload = ByteBuffer.wrap(value.getBytes());
    datastreamEvent.key = ByteBuffer.wrap(key.getBytes());
    datastreamEvent.metadata = metadata;
    return datastreamEvent;
  }

  private DatastreamEventMetadata.OpCode getOpCode(int binlogEventType) {
    switch (binlogEventType) {
      case UpdateRowsEvent.EVENT_TYPE:
      case UpdateRowsEventV2.EVENT_TYPE:
        return DatastreamEventMetadata.OpCode.UPDATE;
      case DeleteRowsEvent.EVENT_TYPE:
      case DeleteRowsEventV2.EVENT_TYPE:
        return DatastreamEventMetadata.OpCode.DELETE;
      case WriteRowsEvent.EVENT_TYPE:
      case WriteRowsEventV2.EVENT_TYPE:
        return DatastreamEventMetadata.OpCode.INSERT;
      default:
        throw new DatastreamRuntimeException("Unknown event of type " + binlogEventType);
    }
  }

  private void processTableMapEvent(TableMapEvent tme) {
    String mysqlDbTableName = tme.getDatabaseName().toString() + "." + tme.getTableName().toString();
    long tableId = tme.getTableId();
    _currDBTableNamesInTransaction.put(tableId, mysqlDbTableName);
  }

  private void rollbackTransaction(BinlogEventV4 event) {
    resetTransactionData();
  }

  private void resetTransactionData() {
    _currDBTableNamesInTransaction.clear();
    _scn = 0;
    _isBeginTxnSeen = false;
    _eventsInTransaction.clear();
  }

  private boolean isRollbackEvent(BinlogEventV4 event) {
    if (event instanceof QueryEvent) {
      QueryEvent qe = (QueryEvent) event;
      String sql = qe.getSql().toString();
      if ("ROLLBACK".equalsIgnoreCase(sql)) {
        LOG.debug("ROLLBACK sql: " + sql);
        return true;
      }
    }
    return false;
  }

  private void endTransaction(BinlogEventV4 e) {
    LOG.info("Ending transaction " + _scn);
    if (_eventsInTransaction.size() > 0) {
      // Write events to the producer only if there are events in this transaction.
      DatastreamProducerRecordBuilder builder = new DatastreamProducerRecordBuilder();
      // TODO we need to support mysql connector that can write to multi partition destination.
      builder.setPartition(0);
      String checkpoint =
          MysqlCheckpoint.createCheckpointString(_sourceId, _scn, _currFileName, _mostRecentSeenEventPosition);
      builder.setSourceCheckpoint(checkpoint);
      _eventsInTransaction.forEach(builder::addEvent);
      _producer.send(builder.build(), (metadata, exception) -> {
        if (exception == null) {
          LOG.debug(String.format("Sending event succeeded, metadata:{%s}", metadata));
        } else {
          // TODO we need to handle this by closing the producer and moving to the old checkpoint.
          LOG.error(String.format("Sending event failed, metadata:{%s}", metadata), exception);
        }
      });
    }

    resetTransactionData();
  }

  private boolean isEndTransactionEvent(BinlogEventV4 event) {
    if (event instanceof QueryEvent) {
      QueryEvent qe = (QueryEvent) event;
      String sql = qe.getSql().toString();
      if ("COMMIT".equalsIgnoreCase(sql)) {
        LOG.debug("COMMIT sql: " + sql);
        return true;
      }
    } else if (event instanceof XidEvent) {
      // A transaction can end with either COMMIT or XID event.
      XidEvent xe = (XidEvent) event;
      long xid = xe.getXid();
      LOG.debug("Treating XID event with xid = " + xid + " as commit for the transaction");
      return true;
    }

    return false;
  }

  private void handleRotateEvent(BinlogEventV4 event) {
    // just log the information and call the handler listener
    RotateEvent re = (RotateEvent) event;
    String fileName = re.getBinlogFileName().toString();
    // Current fileName now points to the new file. This gets checkpointed immediately after the next transaction.
    _currFileName = fileName;
    LOG.info("File Rotated : New fileName: " + fileName + ", current fileName " + _currFileName);
  }

  private String getSourceId(BinlogEventV4 event) {
    if (event instanceof GtidEvent) {
      return buildSourceIdString(((GtidEvent) event).getSourceId());
    } else {
      return DEFAULT_SOURCE_ID;
    }
  }

  private String buildSourceIdString(byte[] sourceId) {
    return byteArrayToHex(sourceId, 0, 4) +
        "-" +
        byteArrayToHex(sourceId, 4, 2) +
        "-" +
        byteArrayToHex(sourceId, 6, 2) +
        "-" +
        byteArrayToHex(sourceId, 8, 2) +
        "-" +
        byteArrayToHex(sourceId, 12, 6);
  }

  private String byteArrayToHex(byte[] a, int offset, int len) {
    StringBuilder sb = new StringBuilder();

    for (int idx = offset; idx < offset + len && idx < a.length; ++idx) {
      sb.append(String.format("%02x", new Object[]{Integer.valueOf(a[idx] & 255)}));
    }

    return sb.toString();
  }

  private long getScn(BinlogEventV4 event) {
    if (event instanceof GtidEvent) {
      return ((GtidEvent) event).getTransactionId();
    } else {
      throw new DatastreamRuntimeException("Event is not a GtidEvent");
    }
  }

  private boolean isEventIgnorable(BinlogEventV4 event) {
    // Beginning of Txn
    if (event instanceof FormatDescriptionEvent) {
      _currFileName = ((FormatDescriptionEvent) event).getBinlogFilename();
      // this event provides information about the structure of the following events
      // this information is already processed and used by the Open Replicator
      LOG.info("FormatDescriptionEvent received(binlog rotated?).");
      return true;
    } else if (event instanceof StopEvent) {
      // this event is generated when mysql stops. We currently ignore it.
      LOG.warn("StopEvent received(mysqld stopped?). We ignore it.");
      return true;
    }

    if (!_isBeginTxnSeen) {
      if (checkTransactionStartEvent(event)) {
        _isBeginTxnSeen = true;
        return false;
      }
      return true;
    }

    return false;
  }

  private boolean checkTransactionStartEvent(BinlogEventV4 event) {
    if (event instanceof GtidEvent) {
      return true;
    } else if (event instanceof QueryEvent) {
      // Process BEGIN event here
      QueryEvent qe = (QueryEvent) event;
      String sql = qe.getSql().toString();
      if ("BEGIN".equalsIgnoreCase(sql)) {
        return true;
      }
    }
    return false;
  }
}
