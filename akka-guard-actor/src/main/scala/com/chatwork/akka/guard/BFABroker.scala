package com.chatwork.akka.guard

import akka.actor._

class BFABroker[T, R](config: BFABrokerConfig[T, R]) extends Actor {
  type GuardMessage = BFAMessage[T, R]

  override def receive: Receive = {
    case msg: GuardMessage =>
      context
        .child(BFABlockerActor.name(msg.id))
        .fold(createAndForward(msg, msg.id))(forwardMsg(msg))
  }

  private def forwardMsg(msg: GuardMessage)(childRef: ActorRef): Unit =
    childRef forward msg

  private def createBFABlocker(id: String): ActorRef =
    context.actorOf(BFABlockerActor.props(id, config), BFABlockerActor.name(id))

  private def createAndForward(msg: GuardMessage, id: String): Unit =
    forwardMsg(msg)(createBFABlocker(id))

}
