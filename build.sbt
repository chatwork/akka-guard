val scala211Version = "2.11.12"
val scala212Version = "2.12.13"
val scala213Version = "2.13.4"

val commonSettings = Seq(
  sonatypeProfileName := "com.chatwork",
  organization := "com.chatwork",
  scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-encoding",
      "UTF-8",
      "-language:_"
    ) ++ {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2L, scalaMajor)) if scalaMajor == 13 => Seq.empty
        case Some((2L, scalaMajor)) if scalaMajor == 12 => Seq.empty
        case Some((2L, scalaMajor)) if scalaMajor <= 11 => Seq("-Yinline-warnings")
      }
    },
  resolvers ++= Seq(
      Resolver.sonatypeRepo("snapshots"),
      Resolver.sonatypeRepo("releases")
    ),
  libraryDependencies ++= Seq(
      ScalaTest.scalatest      % Test,
      ScalaTestPlus.scalacheck % Test,
      ScalaCheck.scalaCheck    % Test,
      Enumeratum.latest,
      ScalaLangModules.java8Compat
    ),
  updateOptions := updateOptions.value.withCachedResolution(true),
  parallelExecution in Test := false,
  javaOptions in (Test, run) ++= Seq("-Xms4g", "-Xmx4g", "-Xss10M", "-XX:+CMSClassUnloadingEnabled"),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  pomExtra := {
    <url>https://github.com/chatwork/akka-guard</url>
    <licenses>
      <license>
        <name>The MIT License</name>
        <url>http://opensource.org/licenses/MIT</url>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:chatwork/akka-guard.git</url>
      <connection>scm:git:github.com/chatwork/akka-guard</connection>
      <developerConnection>scm:git:git@github.com:chatwork/akka-guard.git</developerConnection>
    </scm>
    <developers>
      <developer>
        <id>yoshiyoshifujii</id>
        <name>Yoshitaka Fujii</name>
      </developer>
    </developers>
  },
  publishTo in ThisBuild := sonatypePublishTo.value,
  credentials := {
    val ivyCredentials = (baseDirectory in LocalRootProject).value / ".credentials"
    Credentials(ivyCredentials) :: Nil
  }
)

lazy val `akka-guard-core` = (project in file("akka-guard-core"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-core",
    scalaVersion := scala211Version,
    crossScalaVersions := Seq(scala211Version, scala212Version, scala213Version),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2L, scalaMajor)) if scalaMajor == 13 =>
          Seq(
            Akka.Version2_6.testKit % Test,
            Cats.Version2_1.core,
            Circe.Version0_13.core,
            Circe.Version0_13.generic,
            Circe.Version0_13.parser,
            Akka.Version2_6.actor,
            Akka.Version2_6.slf4j,
            Logback.classic
          )
        case Some((2L, scalaMajor)) if scalaMajor == 12 =>
          Seq(
            Akka.Version2_6.testKit % Test,
            ScalaLangModules.collectionCompat,
            Cats.Version2_1.core,
            Circe.Version0_13.core,
            Circe.Version0_13.generic,
            Circe.Version0_13.parser,
            Akka.Version2_6.actor,
            Akka.Version2_6.slf4j,
            Logback.classic
          )
        case Some((2L, scalaMajor)) if scalaMajor == 11 =>
          Seq(
            Akka.Version2_5.testKit % Test,
            ScalaLangModules.collectionCompat,
            Cats.Version2_0.core,
            Circe.Version0_11.core,
            Circe.Version0_11.generic,
            Circe.Version0_11.parser,
            Akka.Version2_5.actor,
            Akka.Version2_5.slf4j
          )
      }
    }
  )

lazy val `akka-guard-http` = (project in file("akka-guard-http"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-http",
    scalaVersion := scala211Version,
    crossScalaVersions := Seq(scala211Version, scala212Version, scala213Version),
    libraryDependencies ++= Seq(
        AkkaHttp.testKit % Test,
        AkkaHttp.http
      ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2L, scalaMajor)) if scalaMajor == 13 =>
          Seq(
            Akka.Version2_6.testKit       % Test,
            Akka.Version2_6.streamTestKit % Test,
            Akka.Version2_6.stream
          )
        case Some((2L, scalaMajor)) if scalaMajor == 12 =>
          Seq(
            Akka.Version2_6.testKit       % Test,
            Akka.Version2_6.streamTestKit % Test,
            Akka.Version2_6.stream
          )
        case Some((2L, scalaMajor)) if scalaMajor == 11 =>
          Seq(
            Akka.Version2_5.testKit       % Test,
            Akka.Version2_5.streamTestKit % Test,
            Akka.Version2_5.stream
          )
      }
    }
  )
  .dependsOn(`akka-guard-core`)

lazy val `akka-guard-core-typed` = (project in file("akka-guard-core-typed"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-core-typed",
    scalaVersion := scala213Version,
    libraryDependencies ++= Seq(
        Akka.Version2_6.testKitTyped % Test,
        Cats.Version2_1.core,
        Circe.Version0_13.core,
        Circe.Version0_13.generic,
        Circe.Version0_13.parser,
        Akka.Version2_6.actorTyped,
        Akka.Version2_6.slf4j,
        Logback.classic
      )
  )

lazy val `akka-guard-http-typed` = (project in file("akka-guard-http-typed"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-http-typed",
    scalaVersion := scala213Version,
    libraryDependencies ++= Seq(
        AkkaHttp.testKit              % Test,
        Akka.Version2_6.streamTestKit % Test,
        Akka.Version2_6.testKitTyped  % Test,
        AkkaHttp.http,
        Akka.Version2_6.streamTyped
      )
  )
  .dependsOn(`akka-guard-core-typed`)

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard"
  )
  .aggregate(`akka-guard-core`, `akka-guard-http`, `akka-guard-core-typed`, `akka-guard-http-typed`)
