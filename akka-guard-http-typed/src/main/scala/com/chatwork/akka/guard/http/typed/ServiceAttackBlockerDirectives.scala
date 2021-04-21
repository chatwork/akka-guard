package com.chatwork.akka.guard.http.typed

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, Scheduler }
import akka.http.scaladsl.server.{ Directive, Directive0 }
import akka.util.Timeout
import com.chatwork.akka.guard.typed.SABActor.SABMessage
import com.chatwork.akka.guard.typed.SABBroker.SABBrokerMessage

import scala.concurrent.duration._
import scala.util.Try

trait ServiceAttackBlockerDirectives {
  import ServiceAttackBlocker._

  def serviceAttackBlocker(serviceAttackBlocker: ServiceAttackBlocker)(
      id: String
  )(implicit timeout: Timeout = Timeout(3.seconds), scheduler: Scheduler): Directive0 =
    Directive[T] { inner => ctx =>
      val message: ActorRef[Try[R]] => SABBrokerMessage[T, R] = replyTo =>
        SABBrokerMessage(
          SABMessage(
            id,
            request = (),
            handler = a => inner(a)(ctx),
            replyTo
          )
        )
      serviceAttackBlocker.actorRef.ask[Try[R]](a => message(a)).mapTo[R]
    }

}

object ServiceAttackBlockerDirectives extends ServiceAttackBlockerDirectives
