package org.klippelgo.tests.cli

import java.lang.reflect.InvocationTargetException
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.jvmErasure

/** Thrown for a command the CLI cannot make sense of. Its message is all the user sees. */
class UsageException(message: String) : Exception(message)

private val OBJECT_FUNCTIONS = setOf("equals", "hashCode", "toString", "close")

/** The functions of a driver or a peer that the CLI offers: every public one it declares. */
fun commandsOf(target: Any): List<KFunction<*>> =
    target::class.memberFunctions.filter { it.visibility == KVisibility.PUBLIC && it.name !in OBJECT_FUNCTIONS }.sortedBy { it.name }

/** `awaitOffer` as the CLI spells it: `await-offer`. */
fun kebab(name: String): String = name.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }

fun usage(function: KFunction<*>): String = kebab(function.name) + function.parameters.filter { it.kind == KParameter.Kind.VALUE }
    .joinToString("") { p -> if (p.isOptional) " [${kebab(p.name ?: "")}]" else " <${kebab(p.name ?: "")}>" }

/** Calls the function a command names, turning its words into the function's arguments. */
fun call(target: Any, command: String, words: List<String>): Any? {
    val function = commandsOf(target).firstOrNull { kebab(it.name) == command || it.name == command }
        ?: throw UsageException("No \"$command\" here. There is: " + commandsOf(target).joinToString(", ") { kebab(it.name) })
    val values = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
    if (words.size > values.size || words.size < values.count { !it.isOptional }) throw UsageException("Usage: ${usage(function)}")
    val arguments = HashMap<KParameter, Any?>()
    arguments[function.parameters.first()] = target
    values.zip(words).forEach { (parameter, word) -> arguments[parameter] = convert(parameter, word) }
    try {
        return function.callBy(arguments)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}

private fun convert(parameter: KParameter, word: String): Any? = when (parameter.type.jvmErasure) {
    String::class -> word
    Int::class -> word.toInt()
    Long::class -> word.toLong()
    Double::class -> word.toDouble()
    Boolean::class -> word.toBooleanStrict()
    BigDecimal::class -> BigDecimal(word.replace(",", "."))
    BigInteger::class -> BigInteger(word)
    else -> throw UsageException("\"${parameter.name}\" cannot be given on the command line")
}
