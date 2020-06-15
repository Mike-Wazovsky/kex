package org.jetbrains.research.kex.descriptor

import com.abdullin.kthelper.assert.unreachable
import com.abdullin.kthelper.logging.log
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.emptyState
import org.jetbrains.research.kex.state.predicate.axiom
import org.jetbrains.research.kex.state.predicate.require
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.term
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.ir.Class

val Class.isInstantiable: Boolean
    get() = when {
        this.isAbstract -> false
        this.isInterface -> false
        !this.isStatic && this.outerClass != null -> false
        else -> true
    }

fun KexClass.concreteClass(cm: ClassManager): KexClass {
    val kfgKlass = this.kfgClass(cm.type)
    val concrete = when {
        kfgKlass.isInstantiable -> kfgKlass
        else -> ConcreteInstanceGenerator[kfgKlass]
    }
    return concrete.kexType
}

sealed class Descriptor(term: Term, type: KexType, val hasState: Boolean) {
    var term = term
        protected set

    var type = type
        protected set

    val query: PredicateState get() = collectQuery(mutableSetOf())
    val asString: String get() = print(mutableMapOf())

    val typeInfo: PredicateState get() = generateTypeInfo(mutableSetOf())

    val isDefault: Boolean get() = this == descriptor { default(type) }

    override fun toString() = asString

    abstract fun print(map: MutableMap<Descriptor, String>): String
    abstract fun collectQuery(set: MutableSet<Descriptor>): PredicateState

    abstract fun concretize(cm: ClassManager, visited: MutableSet<Descriptor> = mutableSetOf()): Descriptor
    abstract fun deepCopy(copied: MutableMap<Descriptor, Descriptor> = mutableMapOf()): Descriptor
    abstract fun reduce(visited: MutableSet<Descriptor> = mutableSetOf()): Descriptor
    abstract fun generateTypeInfo(visited: MutableSet<Descriptor> = mutableSetOf()): PredicateState
}

sealed class ConstantDescriptor(term: Term, type: KexType) : Descriptor(term, type, false) {
    override fun collectQuery(set: MutableSet<Descriptor>): PredicateState =
            unreachable { log.error("Can't collect query for constant descriptor") }

    override fun concretize(cm: ClassManager, visited: MutableSet<Descriptor>) = this
    override fun deepCopy(copied: MutableMap<Descriptor, Descriptor>) = this
    override fun reduce(visited: MutableSet<Descriptor>) = this
    override fun generateTypeInfo(visited: MutableSet<Descriptor>) = emptyState()

    object Null : ConstantDescriptor(term { const(null) }, KexNull()) {
        override fun print(map: MutableMap<Descriptor, String>) = "null"
    }

    data class Bool(val value: Boolean) : ConstantDescriptor(term { const(value) }, KexBool()) {
        override fun print(map: MutableMap<Descriptor, String>) = "$value"
    }

    data class Int(val value: kotlin.Int) : ConstantDescriptor(term { const(value) }, KexInt()) {
        override fun print(map: MutableMap<Descriptor, String>) = "$value"
    }

    data class Long(val value: kotlin.Long) : ConstantDescriptor(term { const(value) }, KexLong()) {
        override fun print(map: MutableMap<Descriptor, String>) = "$value"
    }

    data class Float(val value: kotlin.Float) : ConstantDescriptor(term { const(value) }, KexFloat()) {
        override fun print(map: MutableMap<Descriptor, String>) = "$value"
    }

    data class Double(val value: kotlin.Double) : ConstantDescriptor(term { const(value) }, KexDouble()) {
        override fun print(map: MutableMap<Descriptor, String>) = "$value"
    }

    data class Class(val value: KexClass) : ConstantDescriptor(term { `class`(value) }, value) {
        override fun print(map: MutableMap<Descriptor, String>) = "$value"
    }
}

class ObjectDescriptor(klass: KexClass) : Descriptor(term { generate(klass) }, klass, true) {
    var klass = klass
        private set

    val fields = mutableMapOf<String, Descriptor>()

    operator fun get(field: String) = fields[field]

    operator fun set(field: String, value: Descriptor) {
        fields[field] = value
    }

    fun remove(field: String): Descriptor? = fields.remove(field)

    fun merge(other: ObjectDescriptor): ObjectDescriptor {
        this.fields += other.fields
        return this
    }

    override fun print(map: MutableMap<Descriptor, String>): String {
        if (this in map) return map[this]!!
        map[this] = term.name
        return buildString {
            append("$term = $klass {\n")
            for ((field, value) in fields) {
                append("    $field = ${value.term}\n")
            }
            append("}\n")
            for ((_, value) in fields) {
                append(value.print(map))
            }
        }
    }

    override fun collectQuery(set: MutableSet<Descriptor>): PredicateState {
        if (this in set) return emptyState()
        set += this
        val builder = StateBuilder()
        builder += axiom { term inequality null }
        for ((field, value) in fields) {
            val fieldTerm = term { term.field(value.type, field) }
            if (value.hasState) {
                builder += value.collectQuery(set)
            }
            builder += require { fieldTerm.load() equality value.term }
        }
        return builder.apply()
    }

    override fun concretize(cm: ClassManager, visited: MutableSet<Descriptor>): ObjectDescriptor {
        if (this in visited) return this
        visited += this

        this.klass = klass.concreteClass(cm)
        this.type = klass
        this.term = term { generate(type) }
        for ((field, value) in fields.toMap()) {
            fields[field] = value.concretize(cm, visited)
        }

        return this
    }

    override fun deepCopy(copied: MutableMap<Descriptor, Descriptor>): Descriptor {
        if (this in copied) return copied[this]!!
        val copy = ObjectDescriptor(klass)
        copied[this] = copy
        for ((field, value) in fields) {
            copy[field] = value.deepCopy(copied)
        }
        return copy
    }

    override fun reduce(visited: MutableSet<Descriptor>): ObjectDescriptor {
        if (this in visited) return this
        visited += this

        for ((field, value) in fields.toMap()) {
            when {
                value.isDefault -> fields.remove(field)
                else -> fields[field] = value.reduce(visited)
            }
        }

        return this
    }

    fun copyWithField(name: String): ObjectDescriptor {
        val copy = this.deepCopy() as ObjectDescriptor
        copy.fields.keys.filter { it != name }.forEach {
            copy.fields.remove(it)
        }
        return copy
    }

    override fun generateTypeInfo(visited: MutableSet<Descriptor>): PredicateState {
        if (this in visited) return emptyState()
        visited += this

        val instanceOfTerm = term { generate(KexBool()) }
        val builder = StateBuilder()
        builder += axiom { instanceOfTerm equality (term `is` this@ObjectDescriptor.type) }
        builder += axiom { instanceOfTerm equality true }
        for ((_, field) in this.fields) {
            builder += field.generateTypeInfo(visited)
        }
        return builder.apply()
    }
}

class ArrayDescriptor(val elementType: KexType, val length: Int) :
        Descriptor(term { generate(KexArray(elementType)) }, KexArray(elementType), true) {
    val elements = mutableMapOf<Int, Descriptor>()

    operator fun set(index: Int, value: Descriptor) {
        elements[index] = value
    }

    override fun print(map: MutableMap<Descriptor, String>): String {
        if (this in map) return map[this]!!
        map[this] = term.name
        return buildString {
            append("$term = $elementType[$length] {\n")
            for ((index, value) in elements) {
                append("    $index = ${value.term}\n")
            }
            append("}\n")
            for ((_, value) in elements) {
                append(value.print(map))
            }
        }
    }

    override fun collectQuery(set: MutableSet<Descriptor>): PredicateState {
        if (this in set) return emptyState()
        set += this
        val builder = StateBuilder()
        builder += axiom { term inequality null }
        elements.forEach { (index, element) ->
            if (element.hasState) {
                builder += element.collectQuery(set)
            }
            builder += require { term[index].load() equality element.term }
        }
        return builder.apply()
    }


    override fun concretize(cm: ClassManager, visited: MutableSet<Descriptor>): ArrayDescriptor {
        if (this in visited) return this
        visited += this
        return this
    }

    override fun deepCopy(copied: MutableMap<Descriptor, Descriptor>): Descriptor {
        if (this in copied) return copied[this]!!
        val copy = ArrayDescriptor(elementType, length)
        copied[this] = copy
        for ((index, value) in elements) {
            copy[index] = value.deepCopy(copied)
        }
        return copy
    }

    override fun reduce(visited: MutableSet<Descriptor>): Descriptor {
        if (this in visited) return this
        visited += this

        for ((index, value) in elements.toMap()) {
            when {
                value.isDefault -> elements.remove(index)
                else -> elements[index] = value.reduce(visited)
            }
        }

        return this
    }

    override fun generateTypeInfo(visited: MutableSet<Descriptor>): PredicateState {
        if (this in visited) return emptyState()
        visited += this

        val instanceOfTerm = term { generate(KexBool()) }
        val builder = StateBuilder()
        builder += axiom { instanceOfTerm equality (term `is` this@ArrayDescriptor.type) }
        builder += axiom { instanceOfTerm equality true }
        for ((_, element) in this.elements) {
            builder += element.generateTypeInfo(visited)
        }
        return builder.apply()
    }
}

class DescriptorBuilder {
    val `null` = ConstantDescriptor.Null
    fun const(@Suppress("UNUSED_PARAMETER") nothing: Nothing?) = `null`
    fun const(value: Boolean) = ConstantDescriptor.Bool(value)
    fun const(number: Number) = when (number) {
        is Long -> ConstantDescriptor.Long(number)
        is Float -> ConstantDescriptor.Float(number)
        is Double -> ConstantDescriptor.Double(number)
        else -> ConstantDescriptor.Int(number.toInt())
    }

    fun `object`(type: KexClass): ObjectDescriptor = ObjectDescriptor(type)
    fun array(length: Int, elementType: KexType): ArrayDescriptor = ArrayDescriptor(elementType, length)

    fun default(type: KexType, nullable: Boolean): Descriptor = descriptor {
        when (type) {
            is KexBool -> const(false)
            is KexByte -> const(0)
            is KexChar -> const(0)
            is KexShort -> const(0)
            is KexInt -> const(0)
            is KexLong -> const(0L)
            is KexFloat -> const(0.0F)
            is KexDouble -> const(0.0)
            is KexClass -> if (nullable) `null` else `object`(type)
            is KexArray -> if (nullable) `null` else array(0, type.element)
            is KexReference -> default(type.reference, nullable)
            else -> unreachable { log.error("Could not generate default descriptor value for unknown type $type") }
        }
    }

    fun default(type: KexType): Descriptor = default(type, false)
}

fun descriptor(body: DescriptorBuilder.() -> Descriptor): Descriptor =
        DescriptorBuilder().body()
