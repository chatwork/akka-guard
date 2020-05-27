package com.chatwork.akka.guard.http

import akka.http.scaladsl.server.{ Directive, Directive0 }
import akka.pattern.ask
import akka.util.Timeout
import com.chatwork.akka.guard._

import scala.concurrent.duration._

trait ServiceAttackBlockerDirectives {
  import ServiceAttackBlocker._

  def serviceAttackBlocker(serviceAttackBlocker: ServiceAttackBlocker, timeout: Timeout = Timeout(3.seconds))(
      id: String
  ): Directive0 =
    Directive[T] { inner => ctx =>
      val message: SABMessage[T, R] = SABMessage(id, (), a => inner(a)(ctx))
      serviceAttackBlocker.actorRef.ask(message)(timeout).mapTo[R]
    }

}

object ServiceAttackBlockerDirectives extends ServiceAttackBlockerDirectives
