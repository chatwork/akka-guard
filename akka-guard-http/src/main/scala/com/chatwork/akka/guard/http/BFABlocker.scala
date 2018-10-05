package com.chatwork.akka.guard.http
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.http.scaladsl.server.RouteResult
import com.chatwork.akka.guard.{ BFABroker, BFABrokerConfig }

case class BFABlocker(
    system: ActorSystem,
    bfaConfig: BFABrokerConfig[Unit, RouteResult],
    actorName: String = "BFABroker"
) {
  import BFABlocker._
  private lazy val broker: BFABroker[T, R] = new BFABroker(bfaConfig)
  private lazy val props: Props            = Props(broker)
  lazy val actorRef: ActorRef              = system.actorOf(props, actorName)
}

object BFABlocker {
  type T = Unit
  type R = RouteResult
}
