package io.github.munzzyy.tern.install

import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind

/** A step of the install pipeline refused to go on. [problem] is already worded for the user. */
class StepFailure(val problem: Problem) : Exception(problem.message) {
    constructor(kind: ProblemKind, message: String, retryAtMs: Long? = null) : this(Problem(kind, message, retryAtMs))

    val kind: ProblemKind get() = problem.kind
}
