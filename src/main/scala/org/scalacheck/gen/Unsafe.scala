package org.scalacheck.gen

import cats.Monad
import cats.syntax.all.*
import org.scalacheck.Gen
import org.scalacheck.Gen.Parameters
import org.scalacheck.rng.Seed

/** Escape hatches for getting `Gen` and an effect monad past each other.
  *
  * `PropertyM` is CPS over `Gen` with the effect confined to the answer type: a step is
  * `(A => Gen[M[Prop]]) => Gen[M[Prop]]`. Generation is pure and `M` sits strictly inside it, so
  * `Gen` must always end up on the *outside*.
  *
  * `PropertyM.run` is where that bites. It holds an `M[A]` and a continuation `A => Gen[M[Prop]]`,
  * but `m.flatMap` needs `A => M[Prop]` — a continuation with the `Gen` already stripped off.
  * Bridging the two means turning `A => Gen[M[Prop]]` into `Gen[A => M[Prop]]`: pulling the `Gen`
  * out from under the arrow. That is [[promote]], and it is the only reason this file exists.
  *
  * The bridge is paid for with a reused random seed (see [[delay]]) — hence `Unsafe`. Ported from
  * QuickCheck's `Test.QuickCheck.Gen.Unsafe`.
  */
object Unsafe {

    /** Commutes `Gen` past `M`: given an effect that yields generators, produce a single generator
      * that yields the effect. QuickCheck's `promote :: Monad m => m (Gen a) -> Gen (m a)`.
      *
      * This cannot be done by running `M` — `Monad[M]` gives no way out of `M`. The trick is to
      * never run it. [[delay]] *generates* an evaluator `eval: Gen[A] => A`, and `m.map(eval)` then
      * pushes evaluation inside `M` without ever observing it.
      *
      * `PropertyM.run` instantiates this at the function (reader) monad, `M := [X] =>> A => X`,
      * which is what converts a continuation `A => Gen[M[Prop]]` into `Gen[A => M[Prop]]`.
      */
    def promote[M[_], A](m: M[Gen[A]])(using monadM: Monad[M]): Gen[M[A]] =
        delay.map(m.map)

    /** A generator whose generated *value* is a function that runs other generators.
      *
      * Evaluating `delay` at `(p, sd)` produces `genA => genA.pureApply(p, sd)` — an evaluator
      * frozen at that one point in generation. Every generator later passed to it is drawn at that
      * same seed, so running one twice through the same evaluator yields the same value. That
      * deliberate seed reuse is the unsafety the module is named for; it is exactly QuickCheck's
      * `delay = MkGen (\r n g -> unGen g r n)`.
      *
      * It is harmless when `M`'s bind invokes the continuation exactly once, as `IO` does. An `M`
      * that invokes it repeatedly (`List`, say) would see identical draws in every branch.
      *
      * Note that `sd` is passed through rather than advanced. Seeds are threaded by whoever
      * consumes randomness — `PropertyM.pick` explicitly resumes its continuation at `ra.seed` —
      * and `delay` consumes none. The observable consequence is that inserting or removing a `run`
      * leaves every subsequent draw untouched, so a recorded failing seed still reproduces after
      * you add one to log something.
      */
    def delay[A]: Gen[Gen[A] => A] =
        Gen.gen((p: Parameters, sd: Seed) =>
            Gen.r(r = Some(genA => genA.pureApply(p, sd)), sd = sd)
        )
}
