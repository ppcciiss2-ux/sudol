package com.korailmacro.app.korail

import java.math.BigInteger
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random

/**
 * Port of the KORAIL app's "DynaPath" anti-automation token (STCLab DynaPath SDK).
 * Faithfully reproduces the algorithm reverse-engineered in the open-source
 * yakisoba0728/korail-mobile-api project (dynapath.py), which the login endpoint
 * requires as the `x-dynapath-m-token` header. Purely deterministic string/integer
 * math — no device attestation or native code involved.
 */
object DynaPath {

    const val HEADER_NAME = "x-dynapath-m-token"
    const val TABLE_INDEX = 1
    private const val DEFAULT_I8 = 161
    private const val DEFAULT_I9 = 30
    private const val DEFAULT_I10 = 2

    private const val BASE_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val RANDOM_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    const val APP_ID = "com.korail.talk"
    const val OS_TYPE = "Android"
    const val SDK_VERSION = "v1.0.3"
    private const val APP_SIGNATURE_HASH = "38ff229cb34c7dda8e28220a2d750cce"
    const val AS_VALUE = "[$APP_SIGNATURE_HASH]"

    private val PRIMES: List<Int> by lazy { primeTable(100) }

    val ENCODING_TABLE: String by lazy { generateEncodingTable(TABLE_INDEX) }

    private fun primeTable(count: Int): List<Int> {
        val primes = mutableListOf<Int>()
        var candidate = 2
        while (primes.size < count + 1) {
            var isPrime = true
            for (p in primes) {
                if (p.toLong() * p.toLong() > candidate) break
                if (candidate % p == 0) {
                    isPrime = false
                    break
                }
            }
            if (isPrime) primes.add(candidate)
            candidate++
        }
        return primes.drop(1)
    }

    private fun sdkPermuteAlphabet(value: String, multiplier: Int, step: Int): String {
        val length = value.length
        var blockSize = 1
        for (prime in PRIMES) {
            if (prime <= length) blockSize = prime else break
        }

        val counts = IntArray(blockSize)
        val chars = arrayOfNulls<Char>(blockSize)
        // factor is kept reduced mod blockSize on every step (rather than accumulating
        // multiplier^idx unbounded) so it can't overflow Long and turn negative for large idx.
        var factor = 1L % blockSize
        for (idx in 0 until blockSize) {
            val target = ((factor * step) % blockSize).toInt()
            counts[target] += 1
            if (counts[target] == 1) {
                chars[idx] = value[target]
            }
            factor = (factor * multiplier) % blockSize
        }

        val encoded = StringBuilder()
        val missing = StringBuilder()
        for (idx in 0 until blockSize) {
            val char = chars[idx]
            if (char != null) {
                encoded.append(char)
                continue
            }
            for (missingIdx in 0 until blockSize) {
                if (counts[missingIdx] == 0) {
                    val replacement = value[missingIdx]
                    chars[idx] = replacement
                    missing.append(replacement)
                    counts[missingIdx] = 1
                    break
                }
            }
        }

        var bs = blockSize
        while (bs < length) {
            missing.append(value[bs])
            bs++
        }

        val missingText = missing.toString()
        return if (missingText.length < PRIMES[0]) {
            encoded.toString() + missingText
        } else {
            encoded.toString() + sdkPermuteAlphabet(missingText, multiplier, step)
        }
    }

    fun generateEncodingTable(index: Int): String {
        val multiplier = PRIMES[index % 29]
        val step = PRIMES[(index / 29) % 29]
        return sdkPermuteAlphabet(BASE_ALPHABET, multiplier, step)
    }

    private fun buildPrefix(table: String, tableIndex: Int, i11: Int, i12: Int): String {
        return "${('a' + tableIndex)}${table[2]}${table[37]}${table[i11]}${table[i12 - 1]}"
    }

    /** Mirrors the SDK's custom string-to-codepoint expansion (CESU-8-like). ASCII input takes the identity path. */
    private fun stringToXa1s(data: String): List<Int> {
        val result = mutableListOf<Int>()
        var i = 0
        while (i < data.length) {
            val cp = data.codePointAt(i)
            when {
                cp < 128 -> result.add(cp)
                cp < 2048 -> {
                    result.add(128 or ((cp shr 7) and 15))
                    result.add(cp and 127)
                }
                cp >= 262144 -> {
                    result.add(160)
                    result.add((cp shr 14) and 127)
                    result.add((cp shr 7) and 127)
                    result.add(cp and 127)
                }
                (63488 and cp) != 55296 -> {
                    result.add(((cp shr 14) and 15) or 144)
                    result.add((cp shr 7) and 127)
                    result.add(cp and 127)
                }
            }
            i += Character.charCount(cp)
        }
        return result
    }

    private fun makeDynapathKey(key: String): BigInteger {
        var value = BigInteger.ZERO
        for (ch in key) {
            val cp = ch.code
            var bit = 32768
            for (n in 0 until 16) {
                if ((bit and cp) != 0) break
                bit = bit shr 1
            }
            value = value.multiply(BigInteger.valueOf((bit shl 1).toLong())).add(BigInteger.valueOf(cp.toLong()))
        }
        return value
    }

    private fun pickTableChar(baseTable: String, remainder: Int, used: String): Char {
        var count = 0
        for (ch in baseTable) {
            if (!used.contains(ch)) {
                if (count == remainder) return ch
                count++
            }
        }
        return ' '
    }

    private fun makeEncodeTable(num: BigInteger, encodeSize: Int, baseTable: String): String {
        val result = StringBuilder()
        var temp = num
        for (i in 0 until encodeSize) {
            val divisor = BigInteger.valueOf((encodeSize - i).toLong())
            val remainder = temp.mod(divisor).toInt()
            result.append(pickTableChar(baseTable, remainder, result.toString()))
            temp = temp.divide(divisor)
        }
        return result.toString()
    }

    private fun encodeNormalBe(data: String, table: String, i8: Int, i9: Int, i10: Int): String {
        val bytesLike = stringToXa1s(data)
        val out = StringBuilder()
        val arr = IntArray(i10 + 1)

        var idx = 0
        val remain0 = bytesLike.size % i10
        val fullLen = bytesLike.size - remain0

        while (idx < fullLen) {
            var value = 0L
            repeat(i10) {
                value = value * i8 + bytesLike[idx]
                idx++
            }
            for (i in 0 until i10 + 1) {
                arr[i] = (value % i9).toInt()
                value /= i9
            }
            for (i in i10 downTo 0) {
                out.append(table[arr[i]])
            }
        }

        var remain = remain0
        if (remain > 0) {
            var value = 0L
            repeat(remain) {
                value = value * i8 + bytesLike[idx]
                idx++
            }
            for (i in 0 until remain + 1) {
                arr[i] = (value % i9).toInt()
                value /= i9
            }
            while (remain >= 0) {
                out.append(table[arr[remain]])
                remain--
            }
        }

        return out.toString()
    }

    /** Matches Python's urllib.parse.quote_plus(value, safe="*-._"). */
    private fun javaUrlEncode(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                c == '*' || c == '-' || c == '.' || c == '_' -> sb.append(c)
                c == ' ' -> sb.append('+')
                else -> sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    private fun javaFormEncode(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (k, v) -> "${javaUrlEncode(k)}=${javaUrlEncode(v)}" }

    data class TokenSettings(
        val deviceId: String,
        val asValue: String = AS_VALUE,
        val appStartTs: String,
        val osVersion: String,
        val deviceModel: String,
        val appId: String = APP_ID,
        val osType: String = OS_TYPE,
        val sdkVersion: String = SDK_VERSION,
        val tableIndex: Int = TABLE_INDEX,
        val table: String = ENCODING_TABLE,
        val i8: Int = DEFAULT_I8,
        val i9: Int = DEFAULT_I9,
        val i10: Int = DEFAULT_I10,
        val secureUser: Boolean = false,
        val debug: Boolean = false,
        val emulator: Boolean = false,
        val hooked: Boolean = false
    )

    fun buildDefaultTokenSettings(osVersion: String, deviceModel: String): TokenSettings =
        TokenSettings(
            deviceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            appStartTs = System.currentTimeMillis().toString(),
            osVersion = osVersion,
            deviceModel = deviceModel
        )

    fun generateToken(
        settings: TokenSettings,
        timestampMs: Long = System.currentTimeMillis(),
        randomText: String = (1..4).map { RANDOM_ALPHABET.random(Random) }.joinToString("")
    ): String {
        val ts = timestampMs.toString()
        val fields = listOf(
            "ai" to settings.appId,
            "di" to settings.deviceId,
            "as" to settings.asValue,
            "su" to settings.secureUser.toString(),
            "dbg" to settings.debug.toString(),
            "emu" to settings.emulator.toString(),
            "hk" to settings.hooked.toString(),
            "it" to settings.appStartTs,
            "ts" to ts,
            "rt" to "0",
            "os" to settings.osVersion,
            "dm" to settings.deviceModel,
            "st" to settings.osType,
            "sv" to settings.sdkVersion
        )
        val payload = javaFormEncode(fields)
        val dynKey = "${settings.sdkVersion}+$randomText+$ts"
        val encodedKey = encodeNormalBe(dynKey, settings.table, settings.i8, settings.i9, settings.i10)
        val customTable = makeEncodeTable(makeDynapathKey(dynKey), settings.i9, settings.table)
        val encodedBody = encodeNormalBe(payload, customTable, settings.i8, settings.i9, settings.i10)
        val prefix = buildPrefix(settings.table, settings.tableIndex, settings.i10, settings.i9)
        return "$prefix${settings.table[encodedKey.length]}$encodedKey$encodedBody"
    }
}
