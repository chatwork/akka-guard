package com.chatwork.akka.guard

import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout }

import scala.util.Try

object SABSupervisor {

  def props[T, R](id: String,
                  config: SABBrokerConfig,
                  failedResponse: => Try[R],
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
                          failedResponse: => Try[R],
                          isFailed: R => Boolean,
                          eventHandler: Option[(ID, SABStatus) => Unit] = None)
    extends Actor
    with ActorLogging
    with MessageForwarder {

  override type Message = SABMessage[T, R]

  protected def props(id: ID): Props = SABActor.props(id, config, failedResponse, isFailed, eventHandler)

  config.receiveTimeout.foreach(context.setReceiveTimeout)

  override def receive: Receive = {
    case ReceiveTimeout =>
      log.debug(s"receive timeout")
      context.stop(self)
    case msg: Message =>
      context
        .child(SABActor.name(msg.id))
        .fold(createAndForward(msg, msg.id, props(msg.id), SABActor.name(msg.id)))(forwardMsg(msg))
  }
}
