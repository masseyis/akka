/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.dispatch

import java.util.List

import se.scalablesolutions.akka.util.HashCode
import se.scalablesolutions.akka.stm.Transaction
import se.scalablesolutions.akka.actor.Actor

import java.util.concurrent.atomic.AtomicInteger

trait MessageQueue {
  def append(handle: MessageInvocation)
  def prepend(handle: MessageInvocation)
}

trait MessageInvoker {
  def invoke(message: MessageInvocation)
}

trait MessageDispatcher {
  def messageQueue: MessageQueue
  def registerHandler(key: AnyRef, handler: MessageInvoker)
  def unregisterHandler(key: AnyRef)
  def start
  def shutdown
}

trait MessageDemultiplexer {
  def select
  def acquireSelectedInvocations: List[MessageInvocation]
  def releaseSelectedInvocations
  def wakeUp
}

class MessageInvocation(val receiver: Actor,
                        val message: AnyRef,
                        val future: Option[CompletableFutureResult],
                        val tx: Option[Transaction]) {
  if (receiver == null) throw new IllegalArgumentException("receiver is null")
  if (message == null) throw new IllegalArgumentException("message is null")

  private [akka] val nrOfDeliveryAttempts = new AtomicInteger(0)
  
  def send = synchronized {
    receiver._mailbox.append(this)
    nrOfDeliveryAttempts.incrementAndGet
  }
  
  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver)
    result = HashCode.hash(result, message)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver == receiver &&
    that.asInstanceOf[MessageInvocation].message == message
  }
  
  override def toString(): String = synchronized { 
    "MessageInvocation[message = " + message + ", receiver = " + receiver + ", future = " + future + ", tx = " + tx + "]"
  }
}
