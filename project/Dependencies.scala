import sbt._
import sbt.ModuleID

object ScalaLangModules {
  val java8Compat      = "org.scala-lang.modules" %% "scala-java8-compat"      % "0.9.1"
  val collectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.3.0"
}

object Circe {

  object Version0_11 {
    val version = "0.11.2"
    val core    = "io.circe" %% "circe-core"    % version
    val parser  = "io.circe" %% "circe-parser"  % version
    val generic = "io.circe" %% "circe-generic" % version
  }

  object Version0_13 {
    val version = "0.13.0"
    val core    = "io.circe" %% "circe-core"    % version
    val parser  = "io.circe" %% "circe-parser"  % version
    val generic = "io.circe" %% "circe-generic" % version
  }
}

object ScalaTest {
  val version   = "3.1.2"
  val scalatest = "org.scalatest" %% "scalatest" % version
}

object ScalaTestPlus {
  val version    = "3.1.2.0"
  val scalacheck = "org.scalatestplus" %% "scalacheck-1-14" % version
}

object Cats {

  object Version2_0 {
    val core = "org.typelevel" %% "cats-core" % "2.0.0"
  }

  object Version2_1 {
    val core = "org.typelevel" %% "cats-core" % "2.1.1"
  }
}

object Enumeratum {
  val latest = "com.beachape" %% "enumeratum" % "1.6.1"
}

object ScalaCheck {
  val scalaCheck = "org.scalacheck" %% "scalacheck" % "1.15.1"
}

object Akka {

  object Version2_5 {
    val version       = "2.5.31"
    val actor         = "com.typesafe.akka" %% "akka-actor"          % version
    val stream        = "com.typesafe.akka" %% "akka-stream"         % version
    val slf4j         = "com.typesafe.akka" %% "akka-slf4j"          % version
    val testKit       = "com.typesafe.akka" %% "akka-testkit"        % version
    val streamTestKit = "com.typesafe.akka" %% "akka-stream-testkit" % version
  }

  object Version2_6 {
    val version       = "2.6.5"
    val actor         = "com.typesafe.akka" %% "akka-actor"               % version
    val stream        = "com.typesafe.akka" %% "akka-stream"              % version
    val slf4j         = "com.typesafe.akka" %% "akka-slf4j"               % version
    val actorTyped    = "com.typesafe.akka" %% "akka-actor-typed"         % version
    val streamTyped   = "com.typesafe.akka" %% "akka-stream-typed"        % version
    val testKit       = "com.typesafe.akka" %% "akka-testkit"             % version
    val streamTestKit = "com.typesafe.akka" %% "akka-stream-testkit"      % version
    val testKitTyped  = "com.typesafe.akka" %% "akka-actor-testkit-typed" % version
  }
}

object AkkaHttp {
  val version = "10.1.12"
  val http    = "com.typesafe.akka" %% "akka-http"         % version
  val testKit = "com.typesafe.akka" %% "akka-http-testkit" % version
}

object Logback {
  val version = "1.2.3"
  val classic = "ch.qos.logback" % "logback-classic" % version
}
