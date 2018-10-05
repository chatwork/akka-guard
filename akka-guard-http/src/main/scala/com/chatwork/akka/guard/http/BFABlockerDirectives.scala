package com.chatwork.akka.guard.http

import akka.http.scaladsl.server.{ Directive, Directive0 }
import akka.pattern.ask
import akka.util.Timeout
import com.chatwork.akka.guard._

import scala.concurrent.duration._

trait BFABlockerDirectives {
  import BFABlocker._

  def bfaBlocker(id: String, bfaBlocker: BFABlocker, timeout: Timeout = Timeout(3.seconds)): Directive0 =
    Directive[T] { inner => ctx =>
      val message: BFAMessage[T, R] = BFAMessage(id, (), a => inner(a)(ctx))
      bfaBlocker.actorRef.ask(message)(timeout).mapTo[R]
    }

}

object BFABlockerDirectives extends BFABlockerDirectives
