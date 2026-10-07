package me.chosante.bdataextractor

import me.chosante.common.CharacterClass
import me.chosante.common.CriterionComparison
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemPlayerStateAtom
import me.chosante.common.ItemStatGate

/**
 * Parser of the client's item-criterion language (Ankama's ANTLR `Critere.g`), the expressions the Item table (35)
 * stores per criterion kind:
 *
 * ```
 * expr    := or
 * or      := and (('or' | '||' | 'ou') and)*
 * and     := not (('and' | '&&' | 'et') not)*
 * not     := ('not' | '!' | 'non') not | cmp
 * cmp     := add (('==' | '!=' | '<>' | '<' | '<=' | '>' | '>=') add)?
 * add     := mul (('+' | '-') mul)*
 * mul     := unary (('*' | '/' | '%') unary)*
 * unary   := '-' unary | primary
 * primary := INT | DECIMAL | "STRING" | true | false | vrai | faux | IDENT '(' [expr (',' expr)*] ')' | '(' expr ')' | '#' expr '#'
 * ```
 *
 * Keywords are case-insensitive; `#…#` only hides a part from the in-game tooltip, so it parses as its content.
 */
internal sealed interface Crit {
    /** An integer or decimal literal, as written. */
    data class Num(
        val text: String,
    ) : Crit

    data class Str(
        val value: String,
    ) : Crit

    data class Bool(
        val value: Boolean,
    ) : Crit

    data class Call(
        val name: String,
        val args: List<Crit>,
    ) : Crit

    data class Not(
        val inner: Crit,
    ) : Crit

    data class And(
        val parts: List<Crit>,
    ) : Crit

    data class Or(
        val parts: List<Crit>,
    ) : Crit

    data class Cmp(
        val op: String,
        val left: Crit,
        val right: Crit,
    ) : Crit

    data class Arith(
        val op: String,
        val left: Crit,
        val right: Crit,
    ) : Crit

    data class Neg(
        val inner: Crit,
    ) : Crit

    data class Hidden(
        val inner: Crit,
    ) : Crit
}

internal class CriterionParseException(
    message: String,
) : IllegalArgumentException(message)

/** Parses [text] into a [Crit] tree, or throws [CriterionParseException]. */
internal fun parseCriterionExpression(text: String): Crit = CriterionParser(tokenizeCriterion(text), text).parse()

private enum class TokKind { NUM, STR, ID, OP }

private data class Tok(
    val kind: TokKind,
    val text: String,
)

private val TWO_CHAR_OPS = setOf("&&", "||", "==", "!=", "<>", "<=", ">=")
private const val ONE_CHAR_OPS = "<>!()+-*/%,#"

private fun tokenizeCriterion(text: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() -> {
                var j = i
                while (j < text.length && text[j].isDigit()) j++
                if (j + 1 < text.length && text[j] == '.' && text[j + 1].isDigit()) {
                    j++
                    while (j < text.length && text[j].isDigit()) j++
                }
                out += Tok(TokKind.NUM, text.substring(i, j))
                i = j
            }
            c == '"' -> {
                val end = text.indexOf('"', i + 1)
                if (end < 0) throw CriterionParseException("unterminated string at $i")
                out += Tok(TokKind.STR, text.substring(i + 1, end))
                i = end + 1
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                out += Tok(TokKind.ID, text.substring(i, j))
                i = j
            }
            i + 1 < text.length && text.substring(i, i + 2) in TWO_CHAR_OPS -> {
                out += Tok(TokKind.OP, text.substring(i, i + 2))
                i += 2
            }
            c in ONE_CHAR_OPS -> {
                out += Tok(TokKind.OP, c.toString())
                i++
            }
            else -> throw CriterionParseException("unexpected character '$c' at $i")
        }
    }
    return out
}

private class CriterionParser(
    private val toks: List<Tok>,
    private val source: String,
) {
    private var pos = 0

    private fun peek(): Tok? = toks.getOrNull(pos)

    private fun isKeyword(vararg words: String): Boolean = peek()?.let { it.kind == TokKind.ID && it.text.lowercase() in words } == true

    private fun isOp(vararg ops: String): Boolean = peek()?.let { it.kind == TokKind.OP && it.text in ops } == true

    private fun fail(why: String): Nothing = throw CriterionParseException("$why at token $pos in `$source`")

    fun parse(): Crit {
        val e = or()
        if (pos != toks.size) fail("trailing tokens")
        return e
    }

    private fun or(): Crit {
        val parts = mutableListOf(and())
        while (isKeyword("or", "ou") || isOp("||")) {
            pos++
            parts += and()
        }
        return parts.singleOrNull() ?: Crit.Or(parts)
    }

    private fun and(): Crit {
        val parts = mutableListOf(not())
        while (isKeyword("and", "et") || isOp("&&")) {
            pos++
            parts += not()
        }
        return parts.singleOrNull() ?: Crit.And(parts)
    }

    private fun not(): Crit {
        if (isKeyword("not", "non") || isOp("!")) {
            pos++
            return Crit.Not(not())
        }
        return cmp()
    }

    private fun cmp(): Crit {
        val left = add()
        if (isOp("==", "!=", "<>", "<", "<=", ">", ">=")) {
            val op = toks[pos++].text
            return Crit.Cmp(op, left, add())
        }
        return left
    }

    private fun add(): Crit {
        var left = mul()
        while (isOp("+", "-")) {
            val op = toks[pos++].text
            left = Crit.Arith(op, left, mul())
        }
        return left
    }

    private fun mul(): Crit {
        var left = unary()
        while (isOp("*", "/", "%")) {
            val op = toks[pos++].text
            left = Crit.Arith(op, left, unary())
        }
        return left
    }

    private fun unary(): Crit {
        if (isOp("-")) {
            pos++
            return Crit.Neg(unary())
        }
        return primary()
    }

    private fun primary(): Crit {
        val t = peek() ?: fail("unexpected end")
        return when (t.kind) {
            TokKind.NUM -> {
                pos++
                Crit.Num(t.text)
            }
            TokKind.STR -> {
                pos++
                Crit.Str(t.text)
            }
            TokKind.OP ->
                when (t.text) {
                    "(" -> {
                        pos++
                        val e = or()
                        if (!isOp(")")) fail("expected ')'")
                        pos++
                        e
                    }
                    "#" -> {
                        pos++
                        val e = or()
                        if (!isOp("#")) fail("expected closing '#'")
                        pos++
                        Crit.Hidden(e)
                    }
                    else -> fail("unexpected '${t.text}'")
                }
            TokKind.ID -> {
                pos++
                when (t.text.lowercase()) {
                    "true", "vrai" -> Crit.Bool(true)
                    "false", "faux" -> Crit.Bool(false)
                    else -> {
                        if (!isOp("(")) fail("bare identifier '${t.text}'")
                        pos++
                        val args = mutableListOf<Crit>()
                        if (!isOp(")")) {
                            args += or()
                            while (isOp(",")) {
                                pos++
                                args += or()
                            }
                        }
                        if (!isOp(")")) fail("expected ')' after the arguments of ${t.text}")
                        pos++
                        Crit.Call(t.text, args)
                    }
                }
            }
        }
    }
}

/**
 * Functions a pool item's EQUIP criterion may call. Anything else FAILS the extraction (a new function needs a
 * decision: enforce it, display it, or assume it satisfied), as does a known one in a shape we cannot type.
 */
private object EquipFunctions {
    /** Characteristic reads: `GetCharac("AP")`, `GetCharacMax("AP")`, `GetCharacteristicMax("AP", "target")`. */
    val STAT = setOf("getcharac", "getcharacteristic")
    val STAT_MAX = setOf("getcharacmax", "getcharacteristicmax")

    const val HAS_EQUIPMENT_ID = "hasequipmentid"
    const val HAS_ANOTHER_SAME_EQUIPMENT = "hasanothersameequipment"
    const val IS_BREED = "isbreed"

    /** The player's state outside the build — numeric (compared with a constant) or boolean. */
    val PLAYER_STATE_NUMERIC = setOf("getcompanyrank", "getstasisgauge", "getwakfugauge", "getcrimescore")
    val PLAYER_STATE_BOOLEAN = setOf("isachievementcomplete")
}

/** The `who` arguments of a characteristic / breed read; both name the wearer in an EQUIP criterion. */
private val WEARER_ARGUMENTS = setOf("target", "caster")

/**
 * Types the EQUIP criterion [raw] of item [itemId] into an [ItemEquipCriterion]: the expression must be a conjunction
 * (through `#…#` and double negations) of atoms of the known functions ([EquipFunctions]) or the literals
 * `True` / `False`. [breedIds] maps the client's breed names (`"SACRIER"`) to their numeric breed id, which resolves the
 * project's [CharacterClass] ([CharacterClass.breedId] — the client says SACRIER where the project says SACRIEUR).
 * Throws on anything else: an `or`, a negated conjunction, an unknown function, a characteristic with no project
 * [me.chosante.common.Characteristic], an unknown breed, a non-constant comparison.
 */
internal fun classifyEquipCriterion(
    itemId: Int,
    raw: String,
    breedIds: Map<String, Int>,
): ItemEquipCriterion {
    fun fail(why: String): Nothing = error("Item $itemId EQUIP criterion: $why — `${raw.replace(Regex("\\s+"), " ").trim()}`")

    val expression =
        try {
            parseCriterionExpression(raw)
        } catch (e: CriterionParseException) {
            fail("unparseable (${e.message})")
        }
    val atoms = mutableListOf<Pair<Boolean, Crit>>()

    fun flatten(
        e: Crit,
        negated: Boolean,
    ) {
        when (e) {
            is Crit.Hidden -> flatten(e.inner, negated)
            is Crit.Not -> flatten(e.inner, !negated)
            is Crit.And -> if (negated) fail("a negated conjunction (an `or` in disguise)") else e.parts.forEach { flatten(it, false) }
            is Crit.Or -> fail("an `or`")
            else -> atoms += negated to e
        }
    }
    flatten(expression, false)

    val requires = mutableListOf<Int>()
    val forbids = mutableListOf<Int>()
    val classes = mutableListOf<CharacterClass>()
    var never = false
    var unique = false
    val statGates = mutableListOf<ItemStatGate>()
    val playerState = mutableListOf<ItemPlayerStateAtom>()

    fun intOf(e: Crit): Int? =
        when (e) {
            is Crit.Num -> e.text.toIntOrNull()
            is Crit.Neg -> intOf(e.inner)?.let { -it }
            is Crit.Hidden -> intOf(e.inner)
            else -> null
        }

    fun literal(e: Crit): String =
        when (e) {
            is Crit.Num -> e.text
            is Crit.Str -> e.value
            is Crit.Neg -> "-" + literal(e.inner)
            else -> fail("a non-literal argument $e")
        }

    fun wearerOnly(
        call: Crit.Call,
        from: Int,
    ) {
        call.args.drop(from).forEach { arg ->
            if (arg !is Crit.Str || arg.value.lowercase() !in WEARER_ARGUMENTS) fail("unexpected argument $arg of ${call.name}")
        }
    }

    for ((negated, atom) in atoms) {
        when (atom) {
            is Crit.Bool -> if (atom.value == negated) never = true // `False` (or `not True`): never holds
            is Crit.Call -> {
                when (val name = atom.name.lowercase()) {
                    EquipFunctions.HAS_ANOTHER_SAME_EQUIPMENT -> {
                        if (!negated || atom.args.isNotEmpty()) fail("HasAnotherSameEquipment outside `not HasAnotherSameEquipment()`")
                        unique = true
                    }
                    EquipFunctions.HAS_EQUIPMENT_ID -> {
                        val ids = atom.args.map { intOf(it) ?: fail("a non-integer item id in HasEquipmentId") }
                        if (ids.isEmpty()) fail("HasEquipmentId without an id")
                        when {
                            negated -> forbids += ids
                            // A positive call with several ids could mean "any of them": not a conjunction we can type.
                            ids.size == 1 -> requires += ids
                            else -> fail("a positive HasEquipmentId with several ids")
                        }
                    }
                    EquipFunctions.IS_BREED -> {
                        if (negated) fail("a negated IsBreed")
                        val breed = (atom.args.firstOrNull() as? Crit.Str)?.value ?: fail("IsBreed without a breed name")
                        wearerOnly(atom, 1)
                        val id = breedIds[breed] ?: fail("unknown breed '$breed'")
                        classes += CharacterClass.entries.firstOrNull { it.breedId == id && it != CharacterClass.UNKNOWN } ?: fail("breed '$breed' ($id) has no CharacterClass")
                    }
                    in EquipFunctions.PLAYER_STATE_BOOLEAN ->
                        playerState += ItemPlayerStateAtom(function = atom.name, args = atom.args.map(::literal), negated = negated)
                    in EquipFunctions.STAT, in EquipFunctions.STAT_MAX, in EquipFunctions.PLAYER_STATE_NUMERIC ->
                        fail("$name used as a boolean")
                    else -> fail("unknown function '${atom.name}'")
                }
            }
            is Crit.Cmp -> {
                val call = atom.left as? Crit.Call ?: fail("a comparison whose left side is not a function call")
                val value = intOf(atom.right) ?: fail("a comparison with a non-constant right side")
                val op = CriterionComparison.ofSymbol(atom.op) ?: fail("unknown operator ${atom.op}")
                val comparison = if (negated) op.negated() else op
                when (val name = call.name.lowercase()) {
                    in EquipFunctions.STAT, in EquipFunctions.STAT_MAX -> {
                        val code = (call.args.firstOrNull() as? Crit.Str)?.value ?: fail("$name without a characteristic")
                        wearerOnly(call, 1)
                        val characteristic = ActionCatalog.CHARAC_CODE[code] ?: fail("characteristic '$code' has no project Characteristic")
                        statGates += ItemStatGate(characteristic, max = name in EquipFunctions.STAT_MAX, comparison = comparison, value = value)
                    }
                    in EquipFunctions.PLAYER_STATE_NUMERIC ->
                        playerState += ItemPlayerStateAtom(function = call.name, args = call.args.map(::literal), comparison = comparison, value = value)
                    in EquipFunctions.PLAYER_STATE_BOOLEAN, EquipFunctions.HAS_EQUIPMENT_ID, EquipFunctions.HAS_ANOTHER_SAME_EQUIPMENT, EquipFunctions.IS_BREED ->
                        fail("$name compared with a number")
                    else -> fail("unknown function '${call.name}'")
                }
            }
            else -> fail("an atom of an unsupported shape: $atom")
        }
    }
    if (classes.distinct().size > 1) fail("IsBreed of two different breeds in one conjunction")
    return ItemEquipCriterion(
        itemId = itemId,
        raw = raw,
        requiresItems = requires.distinct(),
        forbidsItems = forbids.distinct(),
        classes = classes.distinct(),
        never = never,
        uniqueEquipped = unique,
        statGates = statGates,
        playerState = playerState
    )
}
