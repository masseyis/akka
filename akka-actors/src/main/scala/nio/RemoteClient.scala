/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.nio

import scala.collection.mutable.HashMap

import protobuf.RemoteProtocol.{RemoteRequest, RemoteReply}
import se.scalablesolutions.akka.actor.{Exit, Actor}
import se.scalablesolutions.akka.dispatch.{DefaultCompletableFutureResult, CompletableFutureResult}
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.Config.config

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.frame.{LengthFieldBasedFrameDecoder, LengthFieldPrepender}
import org.jboss.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder}
import org.jboss.netty.handler.timeout.ReadTimeoutHandler
import org.jboss.netty.util.{TimerTask, Timeout, HashedWheelTimer}

import java.net.InetSocketAddress
import java.util.concurrent.{TimeUnit, Executors, ConcurrentMap, ConcurrentHashMap}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteClient extends Logging {
  val READ_TIMEOUT = config.getInt("akka.remote.client.read-timeout", 10000)
  val RECONNECT_DELAY = config.getInt("akka.remote.client.reconnect-delay", 5000)

  // TODO: add configuration optons: 'HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel)'
  private[akka] val TIMER = new HashedWheelTimer
  private val clients = new HashMap[String, RemoteClient]

  def clientFor(address: InetSocketAddress): RemoteClient = synchronized {
    val hostname = address.getHostName
    val port = address.getPort
    val hash = hostname + ':' + port
    if (clients.contains(hash)) clients(hash)
    else {
      val client = new RemoteClient(hostname, port)
      client.connect
      clients += hash -> client
      client
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClient(hostname: String, port: Int) extends Logging {
  val name = "RemoteClient@" + hostname
  
  @volatile private var isRunning = false 
  private val futures = new ConcurrentHashMap[Long, CompletableFutureResult]
  private val supervisors = new ConcurrentHashMap[String, Actor]

  // TODO is this Netty channelFactory and other options always the best or should it be configurable?
  private val channelFactory = new NioClientSocketChannelFactory(
    Executors.newCachedThreadPool,
    Executors.newCachedThreadPool)

  private val bootstrap = new ClientBootstrap(channelFactory)

  bootstrap.setPipelineFactory(new RemoteClientPipelineFactory(name, futures, supervisors, bootstrap))
  bootstrap.setOption("tcpNoDelay", true)
  bootstrap.setOption("keepAlive", true)

  private var connection: ChannelFuture = _

  def connect = synchronized {
    if (!isRunning) {
      connection = bootstrap.connect(new InetSocketAddress(hostname, port))
      log.info("Starting remote client connection to [%s:%s]", hostname, port)

      // Wait until the connection attempt succeeds or fails.
      connection.awaitUninterruptibly
      if (!connection.isSuccess) {
        log.error("Remote connection to [%s:%s] has failed due to [%s]", hostname, port, connection.getCause)
        connection.getCause.printStackTrace
      }
      isRunning = true
    }
  }

  def shutdown = synchronized {
    if (!isRunning) {
      connection.getChannel.getCloseFuture.awaitUninterruptibly
      channelFactory.releaseExternalResources
    }
  }

  def send(request: RemoteRequest): Option[CompletableFutureResult] = if (isRunning) {
    if (request.getIsOneWay) {
      connection.getChannel.write(request)
      None
    } else {
      futures.synchronized {
        val futureResult = new DefaultCompletableFutureResult(request.getTimeout)
        futures.put(request.getId, futureResult)
        connection.getChannel.write(request)
        Some(futureResult)
      }      
    }
  } else throw new IllegalStateException("Remote client is not running, make sure you have invoked 'RemoteClient.connect' before using it.")

  def registerSupervisorForActor(actor: Actor) =
    if (!actor._supervisor.isDefined) throw new IllegalStateException("Can't register supervisor for " + actor + " since it is not under supervision")
    else supervisors.putIfAbsent(actor._supervisor.get.uuid, actor)

  def deregisterSupervisorForActor(actor: Actor) =
    if (!actor._supervisor.isDefined) throw new IllegalStateException("Can't unregister supervisor for " + actor + " since it is not under supervision")
    else supervisors.remove(actor._supervisor.get.uuid)
  
  def deregisterSupervisorWithUuid(uuid: String) = supervisors.remove(uuid)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteClientPipelineFactory(name: String, 
                                  futures: ConcurrentMap[Long, CompletableFutureResult],
                                  supervisors: ConcurrentMap[String, Actor],
                                  bootstrap: ClientBootstrap) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline = {
    val pipeline = Channels.pipeline()
    pipeline.addLast("timeout", new ReadTimeoutHandler(RemoteClient.TIMER, RemoteClient.READ_TIMEOUT))
    pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4))
    pipeline.addLast("protobufDecoder", new ProtobufDecoder(RemoteReply.getDefaultInstance))
    pipeline.addLast("frameEncoder", new LengthFieldPrepender(4))
    pipeline.addLast("protobufEncoder", new ProtobufEncoder())
    pipeline.addLast("handler", new RemoteClientHandler(name, futures, supervisors, bootstrap))
    pipeline
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelPipelineCoverage { val value = "all" }
class RemoteClientHandler(val name: String,
                          val futures: ConcurrentMap[Long, CompletableFutureResult],
                          val supervisors: ConcurrentMap[String, Actor],
                          val bootstrap: ClientBootstrap)
 extends SimpleChannelUpstreamHandler with Logging {

  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (event.isInstanceOf[ChannelStateEvent] && event.asInstanceOf[ChannelStateEvent].getState != ChannelState.INTEREST_OPS) {
      log.debug(event.toString)
    }
    super.handleUpstream(ctx, event)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      val result = event.getMessage
      if (result.isInstanceOf[RemoteReply]) {
        val reply = result.asInstanceOf[RemoteReply]
        log.debug("Remote client received RemoteReply[\n%s]", reply.toString)
        val future = futures.get(reply.getId)
        if (reply.getIsSuccessful) {
          val message = RemoteProtocolBuilder.getMessage(reply)
          future.completeWithResult(message)
        } else {
          if (reply.hasSupervisorUuid()) {
            val supervisorUuid = reply.getSupervisorUuid
            if (!supervisors.containsKey(supervisorUuid)) throw new IllegalStateException("Expected a registered supervisor for UUID [" + supervisorUuid + "] but none was found")
            val supervisedActor = supervisors.get(supervisorUuid)
            if (!supervisedActor._supervisor.isDefined) throw new IllegalStateException("Can't handle restart for remote actor " + supervisedActor + " since its supervisor has been removed")
            else supervisedActor._supervisor.get ! Exit(supervisedActor, parseException(reply))
          }
          future.completeWithException(null, parseException(reply))
        }
        futures.remove(reply.getId)
      } else throw new IllegalArgumentException("Unknown message received in remote client handler: " + result)
    } catch {
      case e: Exception =>
        log.error("Unexpected exception in remote client handler: %s", e)
        throw e
    }
  }                 

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    RemoteClient.TIMER.newTimeout(new TimerTask() {
      def run(timeout: Timeout) = {
        log.debug("Remote client reconnecting to [%s]", ctx.getChannel.getRemoteAddress)
        bootstrap.connect
      }
    }, RemoteClient.RECONNECT_DELAY, TimeUnit.MILLISECONDS)
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) =
    log.debug("Remote client connected to [%s]", ctx.getChannel.getRemoteAddress)

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) =
    log.debug("Remote client disconnected from [%s]", ctx.getChannel.getRemoteAddress);

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    log.error("Unexpected exception from downstream in remote client: %s", event.getCause)
    event.getCause.printStackTrace
    event.getChannel.close
  }

  private def parseException(reply: RemoteReply) = {
    val exception = reply.getException
    val exceptionType = Class.forName(exception.substring(0, exception.indexOf('$')))
    val exceptionMessage = exception.substring(exception.indexOf('$') + 1, exception.length)
    exceptionType
      .getConstructor(Array[Class[_]](classOf[String]): _*)
      .newInstance(exceptionMessage).asInstanceOf[Throwable]
  }
}
