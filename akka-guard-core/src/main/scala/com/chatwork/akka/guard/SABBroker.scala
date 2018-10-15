package com.chatwork.akka.guard

import akka.actor._

import scala.util.Try

class SABBroker[T, R](config: SABBrokerConfig,
                      failedResponse: Try[R],
                      isFailed: R => Boolean,
                      eventHandler: Option[(ID, SABStatus) => Unit] = None)
    extends Actor {
  type GuardMessage = SABMessage[T, R]

  override def receive: Receive = {
    case msg: GuardMessage =>
      context
        .child(SABActor.name(msg.id))
        .fold(createAndForward(msg, msg.id))(forwardMsg(msg))
  }

  private def forwardMsg(msg: GuardMessage)(childRef: ActorRef): Unit =
    childRef forward msg

  private def createSABlocker(id: String): ActorRef =
    context.actorOf(SABActor.props(id, config, failedResponse, isFailed, eventHandler), SABActor.name(id))

  private def createAndForward(msg: GuardMessage, id: String): Unit =
    forwardMsg(msg)(createSABlocker(id))

}
