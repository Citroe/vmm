ThisBuild / scalaVersion := "2.13.10"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "a-core"

val chiselVersion = "3.6.0"

lazy val root = (project in file("."))
  .settings(
    name := "vmm",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "0.6.0" % "test",
      // Phase 2 reference-model tests read model/artifacts/*.json. Pinned to the
      // version firrtl 1.6.0 already resolves, so this adds no eviction.
      "org.json4s" %% "json4s-native" % "4.0.6" % "test"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
    ),
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
  )
