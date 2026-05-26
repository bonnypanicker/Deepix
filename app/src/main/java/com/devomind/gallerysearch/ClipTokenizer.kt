package com.devomind.gallerysearch

import android.content.Context
import org.json.JSONObject
import java.util.Locale

data class TokenizedText(
    val inputIds: LongArray,
    val attentionMask: LongArray
)

class ClipTokenizer(context: Context) {
    private val vocab: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val byteEncoder: Map<Int, Char> = buildByteEncoder()
    private val cache = HashMap<String, String>()
    private val tokenPattern = Regex(
        """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]+|[^\s\p{L}\p{N}]+""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val startOfTextId: Int
    private val endOfTextId: Int
    private val unknownId: Int

    init {
        val json = JSONObject(AssetUtils.readAssetText(context, "tokenizer.json"))
        val model = json.getJSONObject("model")

        val vocabJson = model.getJSONObject("vocab")
        val vocabMap = LinkedHashMap<String, Int>(vocabJson.length())
        val keys = vocabJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            vocabMap[key] = vocabJson.getInt(key)
        }
        vocab = vocabMap

        startOfTextId = vocab["<|startoftext|>"] ?: 49406
        endOfTextId = vocab["<|endoftext|>"] ?: 49407
        unknownId = vocab["<|endoftext|>"] ?: endOfTextId

        val mergesJson = model.getJSONArray("merges")
        val ranks = HashMap<Pair<String, String>, Int>(mergesJson.length())
        for (index in 0 until mergesJson.length()) {
            val parts = mergesJson.getString(index).split(" ")
            if (parts.size == 2) {
                ranks[parts[0] to parts[1]] = index
            }
        }
        bpeRanks = ranks
    }

    fun encode(query: String): TokenizedText {
        val tokens = ArrayList<Int>(ContextLength)
        tokens += startOfTextId
        for (token in tokenize(normalizeQuery(query))) {
            tokens += token
            if (tokens.size >= ContextLength - 1) break
        }
        tokens += endOfTextId

        val inputIds = LongArray(ContextLength)
        val attentionMask = LongArray(ContextLength)
        val realTokenCount = tokens.size.coerceAtMost(ContextLength)
        for (index in 0 until realTokenCount) {
            inputIds[index] = tokens[index].toLong()
            attentionMask[index] = 1L
        }
        if (realTokenCount == ContextLength) {
            inputIds[ContextLength - 1] = endOfTextId.toLong()
        }
        return TokenizedText(inputIds, attentionMask)
    }

    private fun normalizeQuery(query: String): String {
        val cleaned = query.trim().replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank()) return "a photo"
        return if (cleaned.lowercase(Locale.ROOT).startsWith(PhotoPrefix)) {
            cleaned
        } else {
            "$PhotoPrefix$cleaned"
        }
    }

    private fun tokenize(text: String): List<Int> {
        val output = ArrayList<Int>()
        for (match in tokenPattern.findAll(text.lowercase(Locale.ROOT))) {
            val token = match.value
            if (token == "<|startoftext|>") {
                output += startOfTextId
                continue
            }
            if (token == "<|endoftext|>") {
                output += endOfTextId
                continue
            }

            val byteEncoded = token.toByteArray(Charsets.UTF_8).joinToString(separator = "") { byte ->
                byteEncoder[byte.toInt() and 0xff].toString()
            }
            for (piece in bpe(byteEncoded).split(" ")) {
                output += vocab[piece] ?: unknownId
            }
        }
        return output
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        if (token.isEmpty()) return token

        var word = token.map { it.toString() }.toMutableList()
        word[word.lastIndex] = word.last() + EndOfWord

        while (true) {
            val pairs = getPairs(word)
            val best = pairs.minByOrNull { pair -> bpeRanks[pair] ?: Int.MAX_VALUE } ?: break
            if (!bpeRanks.containsKey(best)) break

            val nextWord = ArrayList<String>(word.size)
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex && word[index] == best.first && word[index + 1] == best.second) {
                    nextWord += best.first + best.second
                    index += 2
                } else {
                    nextWord += word[index]
                    index += 1
                }
            }
            word = nextWord
            if (word.size == 1) break
        }

        val result = word.joinToString(" ")
        cache[token] = result
        return result
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (index in 0 until word.lastIndex) {
            pairs += word[index] to word[index + 1]
        }
        return pairs
    }

    private fun buildByteEncoder(): Map<Int, Char> {
        val bytes = ArrayList<Int>()
        bytes += 33..126
        bytes += 161..172
        bytes += 174..255

        val chars = ArrayList<Int>(bytes)
        var next = 0
        for (byte in 0..255) {
            if (!bytes.contains(byte)) {
                bytes += byte
                chars += 256 + next
                next += 1
            }
        }

        return bytes.zip(chars).associate { (byte, codePoint) -> byte to codePoint.toChar() }
    }

    companion object {
        const val ContextLength = 77
        private const val EndOfWord = "</w>"
        private const val PhotoPrefix = "a photo of "
    }
}
