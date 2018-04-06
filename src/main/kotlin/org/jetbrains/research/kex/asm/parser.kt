package org.jetbrains.research.kex.asm

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import org.jetbrains.research.kex.UnknownNameException
import org.jetbrains.research.kfg.CM
import org.jetbrains.research.kfg.ir.BasicBlock
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.MethodDesc
import org.jetbrains.research.kfg.ir.value.SlotTracker
import org.jetbrains.research.kfg.ir.value.Value
import org.jetbrains.research.kfg.type.parseStringToType
import java.util.*

interface ActionValue
class KfgValue(val value: Value) : ActionValue
class IntValue(val value: Int) : ActionValue
class DoubleValue(val value: Double) : ActionValue
class StringValue(val value: String) : ActionValue
class Equation(val lhv: ActionValue, val rhv: ActionValue)

interface Action
abstract class MethodAction(val method: Method) : Action
abstract class BlockAction(val bb: BasicBlock) : Action

class MethodEntry(method: Method) : MethodAction(method)
class MethodReturn(method: Method, val `return`: Equation?) : MethodAction(method)
class MethodThrow(method: Method, val throwable: KfgValue) : MethodAction(method)

class BlockEntry(bb: BasicBlock) : BlockAction(bb)
class BlockJump(bb: BasicBlock) : BlockAction(bb)
class BlockBranch(bb: BasicBlock, val conditions: List<Equation>) : BlockAction(bb)
class BlockSwitch(bb: BasicBlock, val key: Equation) : BlockAction(bb)
class BlockTableSwitch(bb: BasicBlock, val key: Equation) : BlockAction(bb)

class ActionParser : Grammar<Action>() {
    var trackers = Stack<SlotTracker>()
    // keyword tokens
    val `throw` by token("throw")
    val exit by token("exit")
    val enter by token("enter")
    val branch by token("branch")
    val switch by token("switch")
    val tableswitch by token("tableswitch")
    // symbol tokens
    val space by token("\\s+", ignore = true)
    val dot by token("\\.")
    val equality by token("==")
    val openBracket by token("\\(")
    val closeBracket by token("\\)")
    val percent by token("\\%")
    val colon by token(":\\s+")
    val semicolon by token(";\\s+")
    val comma by token(",\\s+")
    val num by token("\\d+")
    val word by token("\\w+")
    val string by token("\"[\\w\\s\\.@\\d]*\"")
    val newline by token("(\\r\\n|\\r|\\n)\\s*")

    fun getTracker() = trackers.peek() ?: throw UnknownNameException("No slot tracker defined")

    // equation
    val valueName by (
            ((percent and word and num) use { "${t1.text}${t2.text}${t3.text}" }) or ((percent and num) use { "${t1.text}${t2.text}" })
            ) use {
        getTracker().getValue(this) ?: throw UnknownNameException("Undefined name $this")
    }
    val blockName by (percent and word) use {
        val name = t1.text + t2.text
        getTracker().getBlock(name) ?: throw UnknownNameException("Undefined name $name")
    }

    val typeName by separatedTerms(word, dot) use { map { it.text } }
    val args by separatedTerms(typeName, comma)
    val methodName by (typeName and -openBracket and optional(args) and -closeBracket and -colon and typeName) use {
        val `class` = CM.getByName(t1.dropLast(1).fold("", { acc, curr -> "$acc/$curr" }).drop(1))
        val methodName = t1.takeLast(1).firstOrNull() ?: throw UnknownNameException("Undefined method $t1")
        val args = t2?.map { parseStringToType(it.fold("", { acc, curr -> "$acc/$curr"}).drop(1)) }?.toTypedArray() ?: arrayOf()
        val rettype = parseStringToType(t3.fold("", { acc, curr -> "$acc/$curr" }).drop(1))
        `class`.getMethod(methodName, MethodDesc(args, rettype))
    }

    val kfgValueParser by valueName use { KfgValue(this) }
    val intValueParser by num use { IntValue(text.toInt()) }
    val doubleValueParser by (num and dot and num) use { DoubleValue((t1.text + t2.text + t3.text).toDouble()) }
    val stringValueParser by string use { StringValue(text.drop(1).dropLast(1)) }

    val valueParser by kfgValueParser or intValueParser or doubleValueParser or stringValueParser

    val equationParser by (valueParser and -space and -equality and -space and valueParser) use { Equation(t1, t2) }
    val equationList by separatedTerms(equationParser, semicolon)


    val methodEntryParser by (methodName and -space and -enter) use {
        trackers.push(this.slottracker)
        MethodEntry(this)
    }
    val methodReturnParser by (methodName and -space and -exit and optional((-colon and equationParser))) use {
        val ret = MethodReturn(t1, t2)
        trackers.pop()
        ret
    }

    val methodThrowParser by (methodName and -space and -`throw` and -space and kfgValueParser) use { MethodThrow(t1, t2) }

    val blockEntryParser by (blockName and -space and -enter) use { BlockEntry(this) }
    val blockJumpParser by (blockName and -space and -exit) use { BlockJump(this) }
    val blockBranchParser by (blockName and -space and -branch and -colon and equationList) use { BlockBranch(t1, t2) }
    val blockSwitchParser by (blockName and -space and -switch and -colon and equationParser) use { BlockSwitch(t1, t2) }
    val blockTableSwitchParser by (blockName and -space and -tableswitch and -colon and equationParser) use { BlockTableSwitch(t1, t2) }

    override val rootParser by (methodEntryParser or methodReturnParser or methodThrowParser or
            blockEntryParser or blockJumpParser or blockBranchParser or blockSwitchParser or blockTableSwitchParser)
}