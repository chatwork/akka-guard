# akka-guard

This Scala library is to protect against attack, typified by Brute-Force Attack.

## Installation

Add the following to your sbt build (2.11.x, 2.12.x, 2.13.x).

### Release Version

```scala
resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies += "com.chatwork" %% "akka-guard-http-typed" % <version>
```

or classic is

```scala
resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies += "com.chatwork" %% "akka-guard-http" % <version>
```

## How to use

### Setting to SABConfig

First, decide the setting value of Service Attack Blocker.

```scala
import com.chatwork.akka.guard.typed.config.{ ExponentialBackoff, SABConfig}

import scala.concurrent.duration._

val sabConfig: SABConfig =
  SABConfig(
    maxFailures = 5L,
    failureDuration = 1 minute,
    backoff = ExponentialBackoff(
      minBackoff = 5 minutes,
      maxBackoff = 1 hour,
      randomFactor = 0.2
    ),
    guardResetTimeout = Some(1 hour)
  )
```

or classic is

```scala
import com.chatwork.akka.guard.{ ExponentialBackoff, SABConfig }

import scala.concurrent.duration._

val sabConfig: SABConfig =
  SABConfig(
    maxFailures = 5L,
    failureDuration = 1 minute,
    backoff = ExponentialBackoff(
      minBackoff = 5 minutes,
      maxBackoff = 1 hour,
      randomFactor = 0.2
    ),
    guardResetTimeout = Some(1 hour)
  )
```

### Setting to ServiceAttackBlocker

Next, setting to ServiceAttackBlocker.

- `failedResponse`: If ServiceAttackBlocker is in the Open state, it will respond as a failed.
- `isFailed`: which determines whether the response is unsuccessful or not.
- `eventHandler`: Events in the Open and Closed states can be handled and used for log output, metrics, etc.

```scala
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.{ Directive0, RejectionHandler, RouteResult }
import com.chatwork.akka.guard.http.typed.ServiceAttackBlocker
import com.chatwork.akka.guard.http.typed.ServiceAttackBlockerDirectives._
import com.chatwork.akka.guard.typed.ID
import com.chatwork.akka.guard.typed.SABActor.SABStatus
import com.chatwork.akka.guard.typed.config.SABConfig

import scala.util.{ Success, Try }

private def rejectionHandler: RejectionHandler = ???

private val failedResponse: Try[ServiceAttackBlocker.R] = Success(
  RouteResult.Complete(HttpResponse(status = StatusCodes.Forbidden))
)

private val isFailed: ServiceAttackBlocker.R => Boolean = {
  case RouteResult.Complete(res) if res.status == StatusCodes.OK => false
  case RouteResult.Rejected(rejections)                          => rejectionHandler(rejections).isDefined
  case _                                                         => true
}

private val eventHandler: Option[(ID, SABStatus) => Unit] =
  Some(
    {
      case (id, SABStatus.Open)   => println(s"$id is open.")
      case (id, SABStatus.Closed) => println(s"$id is closed.")
    }
  )

def myServiceAttackBlocker(actorSystem: ActorSystem, sabConfig: SABConfig): String => Directive0 =
  serviceAttackBlocker(
    ServiceAttackBlocker(actorSystem, sabConfig)(failedResponse, isFailed, eventHandler)
  )
```

or classic is

```scala
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.{ Directive0, RejectionHandler, RouteResult }
import com.chatwork.akka.guard.http.ServiceAttackBlocker
import com.chatwork.akka.guard.http.ServiceAttackBlockerDirectives._
import com.chatwork.akka.guard.{ ID, SABConfig, SABStatus }

import scala.util.{ Success, Try }

private def rejectionHandler: RejectionHandler = ???

private val failedResponse: Try[ServiceAttackBlocker.R] = Success(
  RouteResult.Complete(HttpResponse(status = StatusCodes.Forbidden))
)

private val isFailed: ServiceAttackBlocker.R => Boolean = {
  case RouteResult.Complete(res) if res.status == StatusCodes.OK => false
  case RouteResult.Rejected(rejections)                          => rejectionHandler(rejections).isDefined
  case _                                                         => true
}

private val eventHandler: Option[(ID, SABStatus) => Unit] =
  Some(
    {
      case (id, SABStatus.Open)   => println(s"$id is open.")
      case (id, SABStatus.Closed) => println(s"$id is closed.")
    }
  )

def myServiceAttackBlocker(actorSystem: ActorSystem, sabConfig: SABConfig): String => Directive0 =
  serviceAttackBlocker(
    ServiceAttackBlocker(actorSystem, sabConfig)(failedResponse, isFailed, eventHandler)
  )
```

### Routing Example

```scala
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

val route: Route =
  extractActorSystem { system =>
    path("login" / Segment) { id =>
      myServiceAttackBlocker(system, sabConfig)(id) {
        complete("success")
      }
    }
  }
```

