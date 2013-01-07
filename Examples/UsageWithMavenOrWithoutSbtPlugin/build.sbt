name := "scalaxy-example"

organization := "com.nativelibs4java"

version := "1.0-SNAPSHOT"

scalaVersion := "2.10.0-RC1"

resolvers += Resolver.sonatypeRepo("snapshots")

autoCompilerPlugins := true

addCompilerPlugin("com.nativelibs4java" %% "scalaxy" % "0.3-SNAPSHOT")

scalacOptions += "-Xplugin-require:Scalaxy"

scalacOptions += "-Xprint:scalaxy-rewriter"
