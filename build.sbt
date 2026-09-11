ThisBuild / scalaVersion := "3.3.7"
// Published via JitPack, which serves artifacts under `com.github.<org>` and resolves the requested
// version against a git tag (trying both `X` and `vX`). So the groupId must be
// `com.github.cardano-hydrozoa` and `version` must match the release tag — to cut `v0.1.0`, set
// `version := "0.1.0"` here, commit, then tag `v0.1.0`. JitPack strips the Scala `_3` suffix and
// re-serves under the repo name, so consumers use a single `%`:
//   "com.github.cardano-hydrozoa" % "scalacheck-propertym" % "0.1.0"   (+ the JitPack resolver)
ThisBuild / organization := "com.github.cardano-hydrozoa"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
    .settings(
      // Matches the GitHub repo name so the JitPack coordinate reads `…:scalacheck-propertym:…`.
      name := "scalacheck-propertym",
      description :=
          "A monadic property API for ScalaCheck (interleave cats-effect IO with generation and " +
              "assertions), ported from Haskell QuickCheck's Test.QuickCheck.Monadic.",
      licenses := Seq("BSD-3-Clause" -> url("https://opensource.org/license/bsd-3-clause")),
      libraryDependencies ++= Seq(
        // PropertyM reaches into ScalaCheck internals (Gen.gen / doApply / promote / Prop.startSeed),
        // which are version-sensitive — pin the exact version consumers resolve.
        "org.scalacheck" %% "scalacheck" % "1.18.0",
        "org.typelevel" %% "cats-core" % "2.13.0",
        "org.typelevel" %% "cats-effect" % "3.6.3",
        "org.typelevel" %% "cats-effect-testkit" % "3.6.3"
      ),
      scalacOptions ++= Seq(
        "-feature",
        "-deprecation",
        "-unchecked",
        "-language:implicitConversions",
        "-Wvalue-discard",
        "-Wunused:all",
        "-Wall",
        "-Wconf:msg=interpolation uses toString:s",
        "-Yretain-trees"
      )
    )
