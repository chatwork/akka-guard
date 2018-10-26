package com.chatwork.akka.guard

import akka.actor._

import scala.util.Try

class SABBroker[T, R](config: SABConfig,
                      failedResponse: => Try[R],
                      isFailed: R => Boolean,
                      eventHandler: Option[(ID, SABStatus) => Unit] = None)
    extends Actor
    with MessageForwarder {
  override type Message = SABMessage[T, R]

  protected def props(id: ID): Props = SABSupervisor.props(id, config, failedResponse, isFailed, eventHandler)

  override def receive: Receive = {
    case msg: Message =>
      context
        .child(SABSupervisor.name(msg.id))
        .fold(createAndForward(msg, msg.id, props(msg.id), SABSupervisor.name(msg.id)))(forwardMsg(msg))
  }

}
