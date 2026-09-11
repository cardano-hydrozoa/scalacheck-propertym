# scalacheck-propertym

A **monadic property API for [ScalaCheck](https://scalacheck.org)** — interleave effectful `M[_]`
computations (typically [cats-effect](https://typelevel.org/cats-effect/) `IO`) with property
generation and assertions, then drive the whole thing to a ScalaCheck `Prop`.

It is a Scala 3 / Cats port of Haskell QuickCheck's
[`Test.QuickCheck.Monadic`](https://hackage.haskell.org/package/QuickCheck/docs/Test-QuickCheck-Monadic.html):
`run`, `pick`, `pre`, `wp`, `assert`, `stop`, `monitor`, `forAllM`, and the `monadic*` runners —
plus cats-effect `TestControl` runners for testing time-dependent `IO` on a virtual clock.

## Why

Plain ScalaCheck lets you do side effects anywhere, but there's no first-class way to *sequence*
effects with generation and assertion inside one property. `PropertyM` gives you a monad where you
can `pick` generated values, `run` `IO` actions between them, `assert`, and observe — reading like
QuickCheck's monadic do-notation:

```scala
import cats.effect.IO
import org.scalacheck.{Arbitrary, Gen, Properties}
import org.scalacheck.PropertyM.*

object AdditionSpec extends Properties("Addition"):
    property("commutativity, with IO in between") = monadicIO(
      for {
          a <- pick[IO, Int](Arbitrary.arbitrary[Int])
          _ <- run(IO.println("some IO between generators"))
          b <- pick[IO, Int](Arbitrary.arbitrary[Int])
          _ <- assert(a + b == b + a)
      } yield true
    )
```

Testing time on a virtual clock with `TestControl`:

```scala
import scala.concurrent.duration.*
property("a 10s sleep completes instantly under TestControl") =
    monadicTestControlExecuteEmbed(
      for { _ <- run(IO.sleep(10.seconds)) } yield true
    )
```

`src/test/scala/org/scalacheck/PropertyMTest.scala` is a worked tour of every combinator
(`pick`/`forAllM`, `pre`, `assert`/`assertWith`, `stop`, `monitor`, exception handling, seeding,
and the `TestControl` runners).

## Install

Published via [JitPack](https://jitpack.io/#cardano-hydrozoa/scalacheck-propertym) — add the resolver
and depend on a release tag:

```scala
resolvers += "jitpack" at "https://jitpack.io"
libraryDependencies += "com.github.cardano-hydrozoa" % "scalacheck-propertym" % "0.1.0" % Test
```

Note the **single `%`**: JitPack re-serves the built `scalacheck-propertym_3` artifact under the repo
name with the Scala suffix stripped, so `%%` would not resolve. The jar is a Scala 3 artifact
regardless.

### Version compatibility

`PropertyM` uses ScalaCheck **internals** (`Gen.gen`, `Gen.R#doApply`, `gen.Unsafe.promote`,
`Prop.startSeed`), which are not part of ScalaCheck's stable API. This build pins
**ScalaCheck 1.18.0**; consumers should resolve the same version.

## Building

```bash
sbt compile
sbt test          # one demo property shells out to `factor` (coreutils)
sbt scalafmtCheckAll
```

Uses sbt (see `project/build.properties`). A Nix flake provides a dev shell with a matching JDK,
sbt, scalafmt, and coreutils:

```bash
nix develop
```

## License & attribution

Licensed under the [BSD 3-Clause License](LICENSE).

This is a derivative work — a Scala 3 / Cats port of Haskell
[QuickCheck](https://github.com/nick8325/quickcheck)'s `Test.QuickCheck.Monadic` (BSD-3-Clause;
© Koen Claessen, Björn Bringert, Nick Smallbone), built on
[ScalaCheck](https://github.com/typelevel/scalacheck) (BSD-3-Clause; © Rickard Nilsson). See
[NOTICE](NOTICE) for full attribution.
