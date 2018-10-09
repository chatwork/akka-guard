package com.chatwork.akka.guard.http
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.http.scaladsl.server.RouteResult
import com.chatwork.akka.guard.{ SABBroker, SABBrokerConfig }

case class ServiceAttackBlocker(
    system: ActorSystem,
    sabConfig: SABBrokerConfig[Unit, RouteResult],
    actorName: String = "SABBroker"
) {
  import ServiceAttackBlocker._
  private lazy val broker: SABBroker[T, R] = new SABBroker(sabConfig)
  private lazy val props: Props            = Props(broker)
  lazy val actorRef: ActorRef              = system.actorOf(props, actorName)
}

object ServiceAttackBlocker {
  type T = Unit
  type R = RouteResult
}
