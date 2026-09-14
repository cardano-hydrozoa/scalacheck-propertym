package org.scalacheck

import cats.effect.IO
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.unsafe.implicits.*
import cats.syntax.all.*
import org.scalacheck.Prop.{False, True, Undecided, collect, propBoolean}
import org.scalacheck.rng.Seed
import scala.concurrent.duration.DurationInt
import scala.sys.process.*

object PropertyMTest extends Properties("PropertyM") {

    import PropertyM.*

    // Turn a failed prop into a successful on
    def shouldFail(failingProp: Prop): Prop =
        failingProp.map(failingRes =>
            failingRes.copy(status = if failingRes.status == False then True else False)
        )

    val _ = property("TestControl.execute runner should complete quickly") = {

        /*
      Taken from: https://typelevel.org/cats-effect/docs/core/test-runtime

      > In this program, we are creating a fiber which yields in an infinite loop, always giving control back to the
      > runtime and never making any progress. Then, in the main fiber, we sleep for one second and cancel the other
      > fiber. In both the JVM and JavaScript production runtimes, this program does exactly what you expect and
      > terminates after (roughly) one second.

      > Under TestControl, this program will execute forever and never terminate. What's worse is it will also never
      > reach a point where isDeadlocked is true, because it will never deadlock! This perhaps-unintuitive outcome
      > happens because there is at least one fiber in the program which is active and not sleeping, and so tick will
      > continue giving control to that fiber without ever advancing the clock. It is possible to test this kind of
      > program by using tickOne and advance, but you cannot rely on tick to return control when some fiber is
      > remaining active.
         */
        val pathologicalCase: IO[Boolean] =
            for {
                fiber <- IO.cede.foreverM.start
                _ <- IO.sleep(10.second) *> fiber.cancel
            } yield true

        val startTime = IO.realTime.unsafeRunSync()
        // - Switch this to `monadicIO` to see it fail
        // - Running the pathological case in monadicTestControlExecuteEmbed will hang forever
        val _ = monadicTestControlExecute(
          prop = PropertyM.run(pathologicalCase),
          testControlCallback = tc =>
              // Here, tc : TestControl[Prop] is essentially a "handle" into the TestControl runtime.
              for {
                  // At the start of execution, there are no pending fibers. We need to advance until our `pathologicalCase`
                  // is executing. But we can't call `tick`, because `tick` will keep calling fibers until they return
                  // and `foreverM` won't return and `tick` doesn't pass any time.
                  // Thus, we need to call `tickOne` instead and execute only a single fiber.
                  _ <- tc.tickOne
                  // At this point we'll have both our "sleep" and "forever" fiber running. We can advance time 10
                  // seconds, our sleep fiber will wake up, and cancel the "forever" fiber, and the execution will complete.
                  _ <- tc.advanceAndTick(10.second)
                  res <- tc.results.map {
                      case None => throw RuntimeException("Could not get results from test")
                      case Some(Succeeded(x)) => x
                      case Some(_) =>
                          throw RuntimeException(
                            "Could not get successful results from test ('Some' case)"
                          )
                  }
              } yield res
        ).check()
        val endTime = IO.realTime.unsafeRunSync()
        if endTime - startTime >= 10.seconds
        then throw RuntimeException("TestControl is not speeding up time properly")
        else Prop.passed
    }

    val _ = property("TestControl.executeEmbed runner should complete quickly") = {
        val startTime = IO.realTime.unsafeRunSync()
        // Switch this to `monadicIO` to see it fail
        val _ = monadicTestControlExecuteEmbed(
          for {
              _ <- PropertyM.run(IO.sleep(10.seconds))
          } yield true
        ).check()
        val endTime = IO.realTime.unsafeRunSync()
        if endTime - startTime >= 10.seconds
        then throw RuntimeException("TestControl is not speeding up time properly")
        else Prop.passed

    }

    // This property demonstrates calling out to an external process.
    // Its more interesting in Haskell, because it is _necessarily_ in IO in haskell, whereas regular scala(check)
    // lets you do arbitrary side effects.
    //
    // However, we do wrap the IO in `IO.blocking`, which means it can then be bound in a PropertyM for/yield via `run`
    val _ = property("`factor` cli utility works") = {

        def factor(n: Int): IO[Array[Int]] = {
            def parse(s: String): Array[Int] = s.split("\\s+").tail.map(_.toInt)

            IO.blocking(("factor " ++ n.toString).!!).map(parse)
        }

        monadicIO(
          forAllM(
            gen = Gen.posNum[Int],
            k = n =>
                for {
                    factors <- run(factor(n))
                    _ <- PropertyM.assert(factors.fold(1)(_ * _) == n)
                } yield true
          )
        )
    }

    // This demonstrates two things:
    // - Use of "pick" to avoid nested forAlls; we can bind generated values in the PropertyM monad
    // - That we can do arbitrary effects (IO) in between calls to the generators
    val _ = property("commutativity of integer addition") = monadicIO(for {
        int1 <- pick[IO, Int](Arbitrary.arbitrary[Int])
        _ <- run(IO.println("Run some IO in between the calls."))
        int2 <- pick[IO, Int](Arbitrary.arbitrary[Int])
        _ <- run(IO.println(s"($int1, $int2)"))
        _ <- assert(int1 + int2 == int2 + int1)
    } yield true)

    // Regression guard for the seed-threading in `run` / Unsafe.delay: `delay` bakes in a single
    // sub-seed, so this checks that generator draws stay INDEPENDENT even when interleaved with
    // `run`. If the fixed sub-seed collapsed subsequent picks, a/b/c would be identical every case.
    // Full-range Longs make an accidental collision ~2^-64, so equality here really means seed reuse.
    val _ = property("picks interleaved with run stay independent") = monadicIO(for {
        a <- pick[IO, Long](Gen.choose(Long.MinValue, Long.MaxValue))
        _ <- run(IO.unit)
        b <- pick[IO, Long](Gen.choose(Long.MinValue, Long.MaxValue))
        _ <- run(IO.unit)
        c <- pick[IO, Long](Gen.choose(Long.MinValue, Long.MaxValue))
        _ <- assert(a != b && b != c && a != c)
    } yield true)

    // This demonstrates what it looks like when we generate multiple arguments. They are reported
    // correctly in the error message.
    val _ = property("multiple arguments generated are put into arguments list") = monadicIO(
      for {
          _ <- pick[IO, Int](Arbitrary.arbitrary[Int])
          _ <- pick[IO, String](Gen.asciiPrintableStr)
      } yield true
    ).map(failingRes =>
        failingRes.copy(status = if failingRes.args.length == 2 then True else False)
    )

    // This demonstrates running multiple PropertyM's within the monad.
    // Note that the labels appear to print in reverse order:
    //
    // """
    //  ! PropertyM.assertWith Example: Falsified after 0 passed tests.
    //  > Labels of failing property:
    //  Assertion failed
    //  Failed: My third predicate
    //  Passed: My second predicate
    //  Passed: My first predicate
    // """
    val _ = property("assertWith Example") = shouldFail(
      monadicIO(
        for {
            _ <- assertWith[IO](true, "My first predicate")
            _ <- assertWith[IO](true, "My second predicate")
            _ <- assertWith(false, "My third predicate")
        } yield true
      )
    )

    // Demo: collect the generation statistics for a single generated value
    val _ = property("monitor example 1") = monadicIO(for {
        e <- pick[IO, Int](Gen.choose(0, 10))
        _ <- monitor(collect(e))
    } yield true)

    // Demo: add "Failure!" as a message to the counter example.
    val _ = property("monitor example 2") = {
        // "counterexample" from quickcheck, add a message to a failing property.
        def counterexample(msg: String)(prop: Prop): Prop = msg |: prop

        monadicIO(
          for {
              _ <- monitor(counterexample("Failure!"))
          } yield false
        ).map(failingRes =>
            Prop.Result(
              status = if failingRes.labels.contains("Failure!") then True else False,
              args = failingRes.args,
              collected = failingRes.collected,
              labels = failingRes.labels
            )
        )
    }

    // Demo: using "stop" to halt execution
    val _ = property("stop example") = {
        monadicIO(
          for {
              _ <- stop[IO, Boolean, Unit](true)
          } yield false // usually this would cause the property to fail, but we called "stop"
        )
    }

    // `tailRecM` is part of the cats `Monad` instance; it used to be `???`. This drives it through
    // the monad instance to prove it terminates and returns the right answer (no NotImplementedError).
    val _ = property("tailRecM terminates and does not throw") = {
        val M = monadForPropM[IO]
        monadicIO(M.tailRecM(1000) { n =>
            if n <= 0 then M.pure(Right(true)) else M.pure(Left(n - 1))
        })
    }

    // Using "pre". This demonstrates that test cases are skipped if the pre-condition isn't satisfied.
    // If you examine the output from this function, you'll see that many integer values were generated,
    // all odd ones were discarded (and don't count towards "passed" tests)
    val _ = property("`pre` demo") = {
        monadicIO(
          for {
              int <- pick[IO, Int](
                Gen.frequency(
                  (10, Gen.const(0)),
                  (10, Gen.const(1)),
                  (1, Gen.const(2))
                )
              )
              _ <- monitor[IO](collect(int))
              _ <- pre[IO](int % 2 == 0)
              // _ <- assert(int == 0) // Uncomment this line if you want to see the number of discarded test cases
          } yield true
        )
    }

    val _ = property(
      "`pre` should result in 'undecided' when given an unsatisfiable precondition"
    ) = {
        monadicIO(for {
            _ <- pre(false)
        } yield true).map(undecidedRes =>
            undecidedRes.copy(status = if undecidedRes.status == Undecided then True else False)
        )
    }
    val _ = property("assert[IO](false) should fail") = {
        shouldFail(monadicIO(for {
            _ <- assert(false)
        } yield true))
    }

    val _ = property("assert[Either[Any, _]](false) should fail") = {
        type EA[A] = Either[Any, A]

        // FIXME: Factor out runners for common types
        def runner: EA[Prop] => Prop = {
            case Left(err)   => false :| s"test failed with error: $err"
            case Right(prop) => prop
        }

        shouldFail(
          monadic(
            runner,
            for {
                _ <- assert[EA](false)
            } yield true
          )
        )
    }

    // `monadicIO` should catch otherwise-unhandled exceptions and turn them into properties with the Prop.Exception
    // status.
    val _ = property("demo: thrown exceptions") = {
        val prop = monadicIO(
          for {
              _ <- run(
                throw new RuntimeException("This should just fail the test, not crash the suite")
              )
          } yield true
        )
        prop.map(eRes =>
            eRes.status match {
                case Prop.Exception(e) => eRes.copy(status = True)
                case _                 => eRes.copy(status = False)
            }
        )
    }

    val _ = property("demo: IO.raiseError") = {
        val prop = monadicIO(
          for {
              _ <- run(
                IO.raiseError(
                  new RuntimeException("This should just fail the test, not crash the suite")
                )
              )
          } yield true
        )
        prop.map(eRes =>
            eRes.status match {
                case Prop.Exception(e) => eRes.copy(status = True)
                case _                 => eRes.copy(status = False)
            }
        )
    }

    val _ = property("labelled generators work with `pick`") = {
        monadicIO(
          for {
              _ <- pick[IO, Int](Arbitrary.arbitrary[Int].label("Int"))
              _ <- pick[IO, String](Arbitrary.arbitrary[String].label("String"))
          } yield true
        ).map(res => {
            val labels = res.args.map(_.label)
            if labels.contains("Int") && labels.contains("String")
            then res
            else res.copy(status = Prop.False)
        })
    }

    override def overrideParameters(p: Test.Parameters): Test.Parameters = {
        p
            .withMinSuccessfulTests(100)
            .withInitialSeed(Seed.fromBase64("W28rrQBwU4e2me7TydWPZDGl22_0duuU4iuVz5Y6QxN=").get)
    }

    val _ = property("demo: bound values behave deterministically when given a seed") =
        val prop = monadicIO(
          for {
              int1 <- pick[IO, Int](Arbitrary.arbitrary[Int])
              int2 <- pick[IO, Int](Arbitrary.arbitrary[Int])
              int3 <- pick[IO, Int](Arbitrary.arbitrary[Int])
              _ <- run(IO.println(s"$int1 $int2 $int3"))
          } yield true
        )
        prop
}
