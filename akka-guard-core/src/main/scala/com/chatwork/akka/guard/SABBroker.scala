package com.chatwork.akka.guard

import akka.actor._

class SABBroker[T, R](config: SABBrokerConfig[T, R]) extends Actor {
  type GuardMessage = SABMessage[T, R]

  override def receive: Receive = {
    case msg: GuardMessage =>
      context
        .child(ServiceAttackBlockerActor.name(msg.id))
        .fold(createAndForward(msg, msg.id))(forwardMsg(msg))
  }

  private def forwardMsg(msg: GuardMessage)(childRef: ActorRef): Unit =
    childRef forward msg

  private def createSABlocker(id: String): ActorRef =
    context.actorOf(ServiceAttackBlockerActor.props(id, config), ServiceAttackBlockerActor.name(id))

  private def createAndForward(msg: GuardMessage, id: String): Unit =
    forwardMsg(msg)(createSABlocker(id))

}
