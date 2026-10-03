package me.chosante.ui.state

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Properties

/**
 * Every player-facing release note under `<root>/changes` is well-formed, so a broken note fails `./gradlew test`
 * (locally and in CI) instead of shipping a broken What's new. The format, from CONTRIBUTING.md › Release notes:
 *
 * ```
 * changes/unreleased/<short-slug>.properties     (filed under changes/<version>/ by the release PR)
 *   type=feat                                     feat | fix | perf
 *   scope=cli                                     optional: cli | gui
 *   en=One sentence for players                   required
 *   fr=La même phrase pour les joueurs            required
 *   es=La misma frase                             optional
 * ```
 *
 * All problems are reported at once, each prefixed with its file.
 */
class ChangeFragmentsTest {
    private val changesDir: File =
        System.getProperty("wakfu.changesDir")?.let(::File)
            ?: listOf(File("../changes"), File("changes")).firstOrNull { it.isDirectory }
            ?: File("../changes")

    private val files: List<File> by lazy {
        changesDir
            .walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .toList()
    }

    @Test
    fun `the notes directory is found and holds the released notes`() {
        assertThat(changesDir).isDirectory()
        assertThat(files.filter { it.parentFile.name == "1.12.0" }).hasSizeGreaterThanOrEqualTo(11)
    }

    @Test
    fun `changes slash unreleased keeps its anchor file`() {
        // Without it, the release PR (which files every note away) empties the directory, and git's directory-rename
        // detection then moves a note added by a pull request rebased across that release into the released version.
        assertThat(changesDir.resolve(ANCHOR)).describedAs("changes/$ANCHOR must never be deleted").isFile()
    }

    @Test
    fun `every file is a note in changes slash unreleased or changes slash a version`() {
        val problems =
            files.mapNotNull { file ->
                val path = file.relativeTo(changesDir).invariantSeparatorsPath
                val parts = path.split('/')
                when {
                    path == ANCHOR -> null
                    parts.size != 2 -> "$path: a note lives directly in changes/unreleased/ or changes/<version>/"
                    parts[0] != "unreleased" && !VERSION.matches(parts[0]) -> "$path: '${parts[0]}' is neither 'unreleased' nor a version"
                    !SLUG.matches(parts[1]) -> "$path: name it <short-slug>.properties (lowercase letters, digits and dashes)"
                    else -> null
                }
            }
        assertThat(problems).describedAs("Malformed release-note layout (CONTRIBUTING.md › Release notes)").isEmpty()
    }

    @Test
    fun `every note has a valid type, an English and a French text, and only known keys`() {
        val problems =
            files
                .filter { it.extension == "properties" }
                .flatMap { file ->
                    val path = file.relativeTo(changesDir).invariantSeparatorsPath
                    problemsOf(file).map { "$path: $it" }
                }
        assertThat(problems).describedAs("Malformed release notes (CONTRIBUTING.md › Release notes)").isEmpty()
    }

    @Test
    fun `an unreleased note does not reuse the file name of a released one`() {
        val released = files.filter { it.parentFile.name != "unreleased" }.map { it.name }.toSet()
        val clashes = files.filter { it.parentFile.name == "unreleased" && it.name in released }.map { it.name }
        assertThat(clashes)
            .describedAs("The release PR moves unreleased notes next to released ones: rename these")
            .isEmpty()
    }

    private fun problemsOf(file: File): List<String> {
        val content =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes()))
                    .toString()
            } catch (_: CharacterCodingException) {
                return listOf("not valid UTF-8 — save it as UTF-8")
            }
        val problems = mutableListOf<String>()
        val keys = keysOf(content)
        keys
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { problems += "'$it' is set more than once" }
        (keys.toSet() - KNOWN_KEYS).forEach { problems += "unknown key '$it' (known: ${KNOWN_KEYS.joinToString()})" }

        val fields = Properties().apply { load(content.reader()) }
        val type = fields.getProperty("type")?.trim()
        if (type !in TYPES) problems += "'type' must be one of ${TYPES.joinToString(" | ")}, not '$type'"
        fields.getProperty("scope")?.trim()?.let { scope ->
            if (scope !in SCOPES) problems += "'scope' must be one of ${SCOPES.joinToString(" | ")}, not '$scope'"
        }
        for (language in LANGUAGES) {
            val text = fields.getProperty(language)?.trim()
            when {
                text == null -> if (language in REQUIRED_LANGUAGES) problems += "'$language' is required"
                text.isEmpty() -> problems += "'$language' is empty"
                else -> problems += textProblems(text).map { "'$language': $it" }
            }
        }
        val english = fields.getProperty("en")?.trim()
        if (english != null && english.isNotEmpty() && english == fields.getProperty("fr")?.trim()) {
            problems += "'fr' is a copy of 'en' — translate it"
        }
        return problems
    }

    /** What a player-facing sentence must not look like. */
    private fun textProblems(text: String): List<String> =
        listOfNotNull(
            "one line only".takeIf { '\n' in text || '\r' in text },
            "keep it short: ${text.length} > $MAX_LENGTH characters".takeIf { text.length > MAX_LENGTH },
            "write for players, not a commit subject (drop the '${text.substringBefore(':')}:' prefix)".takeIf { COMMIT_PREFIX.containsMatchIn(text) },
            "no surrounding quotes: the value is everything after '='".takeIf { text.length > 1 && text.first() == text.last() && text.first() in "\"'" }
        )

    /** The keys of a .properties text in order (duplicates kept): skips comments and continuation lines. */
    private fun keysOf(content: String): List<String> {
        val keys = mutableListOf<String>()
        var continued = false
        for (raw in content.lines()) {
            val line = raw.trimStart()
            if (continued) {
                continued = line.endsWithOddBackslashes()
                continue
            }
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) continue
            keys += line.takeWhile { it != '=' && it != ':' && !it.isWhitespace() }
            continued = line.endsWithOddBackslashes()
        }
        return keys
    }

    private fun String.endsWithOddBackslashes() = takeLastWhile { it == '\\' }.length % 2 == 1

    private companion object {
        val TYPES = listOf("feat", "fix", "perf")
        val SCOPES = listOf("cli", "gui")
        val REQUIRED_LANGUAGES = listOf("en", "fr")
        val LANGUAGES = REQUIRED_LANGUAGES + "es"
        val KNOWN_KEYS = setOf("type", "scope") + LANGUAGES
        const val MAX_LENGTH = 280
        const val ANCHOR = "unreleased/.gitkeep"
        val VERSION = Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""")
        val SLUG = Regex("""[a-z0-9]+(-[a-z0-9]+)*\.properties""")
        val COMMIT_PREFIX = Regex("""^(feat|fix|perf|chore|docs|refactor|test|build|ci)(\([^)]*\))?!?:""")
    }
}
