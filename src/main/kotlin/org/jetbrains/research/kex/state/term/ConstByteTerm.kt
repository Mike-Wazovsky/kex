package org.jetbrains.research.kex.state.term

import org.jetbrains.research.kex.state.transformer.Transformer
import org.jetbrains.research.kfg.TF

class ConstByteTerm(val value: Byte) : Term(value.toString(), TF.getByteType(), listOf()) {
    override fun print() = name
    override fun <T: Transformer<T>> accept(t: Transformer<T>) = this
}