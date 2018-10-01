package com.chatwork.akka.guard

import akka.actor._

class BFABroker[T, R](config: BFABrokerConfig[T, R]) extends Actor {
  type Message = BFAMessage[T]

  override def receive: Receive = {
    case msg: Message =>
      context
        .child(BFABlocker.name(msg.id))
        .fold(createAndForward(msg, msg.id))(forwardMsg(msg))
  }

  private def forwardMsg(msg: Message)(childRef: ActorRef): Unit =
    childRef forward msg

  private def createAndForward(msg: Message, id: String): Unit =
    createBFABlocker(id) forward msg

  private def createBFABlocker(id: String): ActorRef =
    context.actorOf(BFABlocker.props(id, config), BFABlocker.name(id))

}
