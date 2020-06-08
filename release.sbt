import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._

releaseCrossBuild := true

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(
    action = { state =>
      val extracted = Project extract state
      extracted.runAggregated(PgpKeys.publishSigned in Global in extracted.get(thisProjectRef), state)
    },
    enableCrossBuild = true
  ),
  releaseStepCommandAndRemaining("akka-guard-core/+publishSigned"),
  releaseStepCommandAndRemaining("akka-guard-http/+publishSigned"),
  releaseStepCommandAndRemaining("akka-guard-core-typed/publishSigned"),
  releaseStepCommandAndRemaining("akka-guard-http-typed/publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
