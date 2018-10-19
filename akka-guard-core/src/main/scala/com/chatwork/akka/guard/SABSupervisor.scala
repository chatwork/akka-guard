package com.chatwork.akka.guard

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, ReceiveTimeout, Terminated }

import scala.util.Try

object SABSupervisor {

  def props[T, R](id: String,
                  config: SABBrokerConfig,
                  failedResponse: Try[R],
                  isFailed: R => Boolean,
                  eventHandler: Option[(ID, SABStatus) => Unit] = None): Props = Props(
    new SABSupervisor[T, R](
      id,
      config,
      failedResponse = failedResponse,
      isFailed = isFailed,
      eventHandler = eventHandler,
    )
  )

  def name(id: String): String = s"SABSupervisor-$id"

}

class SABSupervisor[T, R](id: String,
                          config: SABBrokerConfig,
                          failedResponse: Try[R],
                          isFailed: R => Boolean,
                          eventHandler: Option[(ID, SABStatus) => Unit] = None)
    extends Actor
    with ActorLogging
    with MessageForwarder {

  override type Message = SABMessage[T, R]

  private def props(id: ID) = SABActor.props(id, config, failedResponse, isFailed, eventHandler)

  config.receiveTimeout.foreach(context.setReceiveTimeout)

  override protected def createActor(id: String, props: Props, name: String): ActorRef = {
    val child = super.createActor(id, props, name)
    context.watch(child)
    child
  }

  override def receive: Receive = {
    case ReceiveTimeout =>
      log.debug(s"receive timeout")
      context.stop(self)
    case Terminated(ref) if context.children.toList.contains(ref) =>
      log.debug(s"child terminated: $ref")
      context.stop(self)
    case msg: Message =>
      context
        .child(SABActor.name(msg.id))
        .fold(createAndForward(msg, msg.id, props(msg.id), SABActor.name(msg.id)))(forwardMsg(msg))
  }
}
