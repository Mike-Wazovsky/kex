package org.jetbrains.research.kex.state.predicate

import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.transformer.Transformer

class ThrowPredicate(throwable: Term, type: PredicateType = PredicateType.State()) : Predicate(type, listOf(throwable)) {
    fun getThrowable() = operands[0]

    override fun print() = "throw ${getThrowable()}"

    override fun <T: Transformer<T>> accept(t: Transformer<T>): Predicate {
        val throwable = t.transform(getThrowable())
        return when {
            throwable == getThrowable() -> this
            else -> t.pf.getThrow(throwable, type)
        }
    }
}