/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.Config.config

import org.apache.cassandra.service._
import org.apache.thrift.transport._
import org.apache.thrift.protocol._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object CassandraStorage extends MapStorage 
  with VectorStorage with RefStorage with Logging {
  val KEYSPACE = "akka"
  val MAP_COLUMN_PARENT = new ColumnParent("map", null)
  val VECTOR_COLUMN_PARENT = new ColumnParent("vector", null)
  val REF_COLUMN_PARENT = new ColumnParent("ref", null)
  val REF_KEY = "item".getBytes("UTF-8")
  val EMPTY_BYTE_ARRAY = new Array[Byte](0)

  val CASSANDRA_SERVER_HOSTNAME = config.getString("akka.storage.cassandra.hostname", "127.0.0.1")
  val CASSANDRA_SERVER_PORT = config.getInt("akka.storage.cassandra.port", 9160)
  val CONSISTENCY_LEVEL = {
    config.getString("akka.storage.cassandra.consistency-level", "QUORUM") match {
      case "ZERO" =>   0
      case "ONE" =>    1
      case "QUORUM" => 2
      case "ALL" =>    3
      case unknown => throw new IllegalArgumentException("Consistency level [" + unknown + "] is not supported. Expected one of [ZERO, ONE, QUORUM, ALL]")
    }
  }
  val IS_ASCENDING = true

  @volatile private[this] var isRunning = false
  private[this] val protocol: Protocol = Protocol.Binary
/*   {
     config.getString("akka.storage.cassandra.procotol", "binary") match {
      case "binary" => Protocol.Binary
      case "json" => Protocol.JSON
      case "simple-json" => Protocol.SimpleJSON
      case unknown => throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
    }
  }
*/

  private[this] val serializer: Serializer = {
    config.getString("akka.storage.cassandra.storage-format", "manual") match {
      case "scala-json" => Serializer.ScalaJSON
      case "java-json" =>  Serializer.JavaJSON
      case "protobuf" =>   Serializer.Protobuf
      case "java" =>       Serializer.Java
      case "manual" =>     Serializer.NOOP
      case "sbinary" =>    throw new UnsupportedOperationException("SBinary serialization protocol is not yet supported for storage")
      case "avro" =>       throw new UnsupportedOperationException("Avro serialization protocol is not yet supported for storage")
      case unknown =>      throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
    }
  }

  private[this] val sessions = new CassandraSessionPool(
    KEYSPACE,
    StackPool(SocketProvider(CASSANDRA_SERVER_HOSTNAME, CASSANDRA_SERVER_PORT)),
    protocol,
    CONSISTENCY_LEVEL)

  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(REF_COLUMN_PARENT.getColumn_family, null, REF_KEY),
        serializer.out(element),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getRefStorageFor(name: String): Option[AnyRef] = {
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, new ColumnPath(REF_COLUMN_PARENT.getColumn_family, null, REF_KEY))
      }
      if (column.isDefined) Some(serializer.in(column.get.getColumn.value, None))
      else None
    } catch {
      case e =>
        e.printStackTrace
        None
    }
  }

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(getVectorStorageSizeFor(name))),
        serializer.out(element),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  // FIXME implement
  def insertVectorStorageEntriesFor(name: String, elements: List[AnyRef]) = {
    throw new UnsupportedOperationException("insertVectorStorageEntriesFor for CassandraStorage is not implemented yet")
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(index)),
        serializer.out(elem),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getVectorStorageEntryFor(name: String, index: Int): AnyRef =  {
    val column: Option[ColumnOrSuperColumn] = sessions.withSession {
      _ | (name, new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(index)))
    }
    if (column.isDefined) serializer.in(column.get.column.value, None)
    else throw new NoSuchElementException("No element for vector [" + name + "] and index [" + index + "]")
  }

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[AnyRef] = {
    val startBytes = if (start.isDefined) intToBytes(start.get) else null
    val finishBytes = if (finish.isDefined) intToBytes(finish.get) else null
    val columns: List[ColumnOrSuperColumn] = sessions.withSession {
      _ / (name,
        VECTOR_COLUMN_PARENT,
        startBytes, finishBytes,
        IS_ASCENDING,
        count,
        CONSISTENCY_LEVEL)
    }
    columns.map(column => serializer.in(column.getColumn.value, None))
  }

  def getVectorStorageSizeFor(name: String): Int = {
    sessions.withSession {
      _ |# (name, VECTOR_COLUMN_PARENT)
    }
  }

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: AnyRef, element: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, serializer.out(key)),
        serializer.out(element),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[AnyRef, AnyRef]]) = {
    val batch = new scala.collection.mutable.HashMap[String, List[ColumnOrSuperColumn]]
    for (entry <- entries) {
      val columnOrSuperColumn = new ColumnOrSuperColumn
      columnOrSuperColumn.setColumn(new Column(serializer.out(entry._1), serializer.out(entry._2), System.currentTimeMillis))
      batch + (MAP_COLUMN_PARENT.getColumn_family -> List(columnOrSuperColumn))
    }
    sessions.withSession {
      _ ++| (name, batch, CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] = {
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, serializer.out(key)))
      }
      if (column.isDefined) Some(serializer.in(column.get.getColumn.value, None))
      else None
    } catch {
      case e =>
        e.printStackTrace
        None
    }
  }

  def getMapStorageFor(name: String): List[Tuple2[AnyRef, AnyRef]]  = {
    val size = getMapStorageSizeFor(name)
    sessions.withSession { session =>
      val columns = session / (name, MAP_COLUMN_PARENT, EMPTY_BYTE_ARRAY, EMPTY_BYTE_ARRAY, true, size, CONSISTENCY_LEVEL)
      for {
        columnOrSuperColumn <- columns
        entry = (serializer.in(columnOrSuperColumn.column.name, None), serializer.in(columnOrSuperColumn.column.value, None))
      } yield entry
    }
  }


  def getMapStorageSizeFor(name: String): Int = {
    sessions.withSession {
      _ |# (name, MAP_COLUMN_PARENT)
    }
  }

  def removeMapStorageFor(name: String): Unit = removeMapStorageFor(name, null)

  def removeMapStorageFor(name: String, key: AnyRef): Unit = {
    val keyBytes = if (key == null) null else serializer.out(key)
    sessions.withSession {
      _ -- (name,
        new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, keyBytes),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageRangeFor(name: String, start: Option[AnyRef], finish: Option[AnyRef], count: Int):
  List[Tuple2[AnyRef, AnyRef]] = {
    val startBytes = if (start.isDefined) serializer.out(start.get) else null
    val finishBytes = if (finish.isDefined) serializer.out(finish.get) else null
    val columns: List[ColumnOrSuperColumn] = sessions.withSession {
      _ / (name, MAP_COLUMN_PARENT, startBytes, finishBytes, IS_ASCENDING, count, CONSISTENCY_LEVEL)
    }
    columns.map(column => (column.getColumn.name, serializer.in(column.getColumn.value, None)))
  }
}

/**
 * NOTE: requires command line options:
 * <br/>
 *   <code>-Dcassandra -Dstorage-config=config/ -Dpidfile=akka.pid</code>
 * <p/>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 *
object EmbeddedCassandraStorage extends Logging  {
val KEYSPACE = "akka"
val MAP_COLUMN_FAMILY = "map"
val VECTOR_COLUMN_FAMILY = "vector"
val REF_COLUMN_FAMILY = "ref:item"

val IS_ASCENDING = true

val RUN_THRIFT_SERVICE = akka.akka.config.getBool("akka.storage.cassandra.thrift-server.service", false)
val CONSISTENCY_LEVEL =  {
if (akka.akka.config.getBool("akka.storage.cassandra.blocking", true)) 0
else 1 }

@volatile private[this] var isRunning = false
private[this] val serializer: Serializer =  {
akka.akka.config.getString("akka.storage.cassandra.storage-format", "java") match  {
case "scala-json" => Serializer.ScalaJSON
case "java-json" =>  Serializer.JavaJSON
case "protobuf" =>   Serializer.Protobuf
case "java" =>       Serializer.Java
case "sbinary" =>    throw new UnsupportedOperationException("SBinary serialization protocol is not yet supported for storage")
case "avro" =>       throw new UnsupportedOperationException("Avro serialization protocol is not yet supported for storage")
case unknown =>      throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
}
}

// TODO: is this server thread-safe or needed to be wrapped up in an actor?
private[this] val server = classOf[CassandraServer].newInstance.asInstanceOf[CassandraServer]

private[this] var thriftServer: CassandraThriftServer = _

def start = synchronized  {
if (!isRunning)  {
try  {
server.start
log.info("Cassandra persistent storage has started up successfully");
} catch  {
case e =>
log.error("Could not start up Cassandra persistent storage")
throw e
}
if (RUN_THRIFT_SERVICE)  {
thriftServer = new CassandraThriftServer(server)
thriftServer.start
}
isRunning
}
}

def stop = if (isRunning)  {
//server.storageService.shutdown
if (RUN_THRIFT_SERVICE) thriftServer.stop
}

// ===============================================================
// For Ref
// ===============================================================

def insertRefStorageFor(name: String, element: AnyRef) =  {
server.insert(
KEYSPACE,
name,
REF_COLUMN_FAMILY,
element,
System.currentTimeMillis,
CONSISTENCY_LEVEL)
}

def getRefStorageFor(name: String): Option[AnyRef] =  {
try  {
val column = server.get_column(KEYSPACE, name, REF_COLUMN_FAMILY)
Some(serializer.in(column.value, None))
} catch  {
case e =>
e.printStackTrace
None }
}

// ===============================================================
// For Vector
// ===============================================================

def insertVectorStorageEntryFor(name: String, element: AnyRef) =  {
server.insert(
KEYSPACE,
name,
VECTOR_COLUMN_FAMILY + ":" + getVectorStorageSizeFor(name),
element,
System.currentTimeMillis,
CONSISTENCY_LEVEL)
}

def getVectorStorageEntryFor(name: String, index: Int): AnyRef =  {
try  {
val column = server.get_column(KEYSPACE, name, VECTOR_COLUMN_FAMILY + ":" + index)
serializer.in(column.value, None)
} catch  {
case e =>
e.printStackTrace
throw new Predef.NoSuchElementException(e.getMessage)
}
}

def getVectorStorageRangeFor(name: String, start: Int, count: Int): List[AnyRef]  =
server.get_slice(KEYSPACE, name, VECTOR_COLUMN_FAMILY, IS_ASCENDING, count)
.toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]].map(tuple => tuple._2)

def getVectorStorageSizeFor(name: String): Int =
server.get_column_count(KEYSPACE, name, VECTOR_COLUMN_FAMILY)

// ===============================================================
// For Map
// ===============================================================

def insertMapStorageEntryFor(name: String, key: String, value: AnyRef) =  {
server.insert(
KEYSPACE, name,
MAP_COLUMN_FAMILY + ":" + key,
serializer.out(value),
System.currentTimeMillis,
CONSISTENCY_LEVEL)
}

def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[String, AnyRef]]) =  {
import java.util.{ Map, HashMap, List, ArrayList }
val columns: Map[String, List[column_t]] = new HashMap
for (entry <- entries)  {
val cls: List[column_t] = new ArrayList
cls.add(new column_t(entry._1, serializer.out(entry._2), System.currentTimeMillis))
columns.put(MAP_COLUMN_FAMILY, cls)
}
server.batch_insert(new BatchMutation(
KEYSPACE, name,
columns),
CONSISTENCY_LEVEL)
}

def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] =  {
try  {
val column = server.get_column(KEYSPACE, name, MAP_COLUMN_FAMILY + ":" + key)
Some(serializer.in(column.value, None))
} catch  {
case e =>
e.printStackTrace
None
}
}

def getMapStorageFor(name: String): List[Tuple2[String, AnyRef]]  =  {
val columns = server.get_columns_since(KEYSPACE, name, MAP_COLUMN_FAMILY, -1)
.toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
for  {
column <- columns
col = (column.columnName, serializer.in(column.value, None))
} yield col
}

def getMapStorageSizeFor(name: String): Int =
server.get_column_count(KEYSPACE, name, MAP_COLUMN_FAMILY)

def removeMapStorageFor(name: String) =
server.remove(KEYSPACE, name, MAP_COLUMN_FAMILY, System.currentTimeMillis, CONSISTENCY_LEVEL)

def getMapStorageRangeFor(name: String, start: Int, count: Int): List[Tuple2[String, AnyRef]] =  {
server.get_slice(KEYSPACE, name, MAP_COLUMN_FAMILY, IS_ASCENDING, count)
.toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]]
}
}


class CassandraThriftServer(server: CassandraServer) extends Logging  {
case object Start
case object Stop

private[this] val serverEngine: TThreadPoolServer = try  {
val pidFile = akka.akka.config.getString("akka.storage.cassandra.thrift-server.pidfile", "akka.pid")
if (pidFile != null) new File(pidFile).deleteOnExit();
val listenPort = DatabaseDescriptor.getThriftPort

val processor = new Cassandra.Processor(server)
val tServerSocket = new TServerSocket(listenPort)
val tProtocolFactory = new TBinaryProtocol.Factory

val options = new TThreadPoolServer.Options
options.minWorkerThreads = 64
new TThreadPoolServer(new TProcessorFactory(processor),
tServerSocket,
new TTransportFactory,
new TTransportFactory,
tProtocolFactory,
tProtocolFactory,
options)
} catch  {
case e =>
log.error("Could not start up Cassandra thrift service")
throw e
}

import scala.actors.Actor._
private[this] val serverDaemon = actor  {
receive  {
case Start =>
serverEngine.serve
log.info("Cassandra thrift service has starting up successfully")
case Stop =>
log.info("Cassandra thrift service is shutting down...")
serverEngine.stop
}
}

def start = serverDaemon ! Start
def stop = serverDaemon ! Stop
}
 */
