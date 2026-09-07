package com.lchuang.xiaozhimobile.extensions

import java.util.Locale

/** Capabilities that an extension may declare for user review. */
enum class ExtensionPermission(val wireName: String) {
    NETWORK("network"),
    READ_SCREEN("read_screen"),
    ACCESSIBILITY("accessibility"),
    LOCATION("location"),
    PHONE_CONTROL("phone_control"),
    MEDIA_CONTROL("media_control"),
    MESSAGING("messaging"),
    FILES("files"),
    VISION("vision"),
    IMAGE_GENERATION("image_generation"),
    MCP("mcp"),
    CAMERA("camera"),
    MICROPHONE("microphone"),
    NOTIFICATIONS("notifications");

    companion object {
        fun fromWire(value: String): ExtensionPermission? {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { permission -> permission.wireName == normalized }
        }
    }
}

/** A parsed, declarative extension manifest. It contains no executable payload. */
data class ExtensionManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String = "",
    val permissions: Set<ExtensionPermission> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    /** SHA-256 digests for every payload entry other than manifest.json. */
    val files: Map<String, String> = emptyMap(),
) {
    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9._-]{2,63}")
        private val VERSION_PATTERN = Regex(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?",
        )
        private val DIGEST_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val CAPABILITY_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        private const val MAX_DESCRIPTION_LENGTH = 4_096
        private const val MAX_ARRAY_ITEMS = 64
        private const val MAX_FILES = 512

        @Throws(ExtensionManifestException::class)
        fun parse(json: String): ExtensionManifest {
            if (json.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_TOO_LARGE,
                    "manifest exceeds the size limit",
                )
            }

            val objectValue = try {
                ManifestJsonParser(json).parseObject()
            } catch (error: ManifestJsonException) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_INVALID,
                    "manifest is not a JSON object",
                    error,
                )
            }

            val id = requiredString(objectValue, "id")
            if (!ID_PATTERN.matches(id)) {
                throw ExtensionManifestException(XzPackValidationCode.INVALID_ID, "invalid extension id")
            }
            val version = requiredString(objectValue, "version")
            if (!VERSION_PATTERN.matches(version)) {
                throw ExtensionManifestException(XzPackValidationCode.INVALID_VERSION, "invalid extension version")
            }
            val name = requiredString(objectValue, "name")
            if (name.length > 128) {
                throw ExtensionManifestException(XzPackValidationCode.MANIFEST_INVALID, "extension name is too long")
            }

            val description = optionalString(objectValue, "description")
            if (description.length > MAX_DESCRIPTION_LENGTH) {
                throw ExtensionManifestException(XzPackValidationCode.MANIFEST_INVALID, "description is too long")
            }

            val permissions = parsePermissions(objectValue["permissions"])
            val capabilities = parseCapabilities(objectValue["capabilities"])
            val files = parseFiles(objectValue["files"])

            return ExtensionManifest(
                id = id,
                version = version,
                name = name,
                description = description,
                permissions = permissions,
                capabilities = capabilities,
                files = files,
            )
        }

        private fun requiredString(objectValue: Map<String, Any?>, key: String): String {
            val value = objectValue[key]
            if (value !is String || value.isBlank() || value != value.trim()) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_INVALID,
                    "$key must be a non-blank string",
                )
            }
            return value
        }

        private fun optionalString(objectValue: Map<String, Any?>, key: String): String {
            if (!objectValue.containsKey(key) || objectValue[key] == null) return ""
            val value = objectValue[key]
            if (value !is String || value != value.trim()) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_INVALID,
                    "$key must be a string",
                )
            }
            return value
        }

        private fun parsePermissions(value: Any?): Set<ExtensionPermission> {
            if (value == null) return emptySet()
            if (value !is List<*> || value.size > MAX_ARRAY_ITEMS) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_INVALID,
                    "permissions must be a short array",
                )
            }
            val result = linkedSetOf<ExtensionPermission>()
            for (raw in value) {
                if (raw !is String || raw.isBlank() || raw != raw.trim()) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.MANIFEST_INVALID,
                        "permission must be a string",
                    )
                }
                val permission = ExtensionPermission.fromWire(raw)
                    ?: throw ExtensionManifestException(
                        XzPackValidationCode.UNKNOWN_PERMISSION,
                        "unknown extension permission",
                    )
                if (!result.add(permission)) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.MANIFEST_INVALID,
                        "duplicate extension permission",
                    )
                }
            }
            return result
        }

        private fun parseCapabilities(value: Any?): Set<String> {
            if (value == null) return emptySet()
            if (value !is List<*> || value.size > MAX_ARRAY_ITEMS) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_INVALID,
                    "capabilities must be a short array",
                )
            }
            val result = linkedSetOf<String>()
            for (raw in value) {
                if (raw !is String || !CAPABILITY_PATTERN.matches(raw)) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.MANIFEST_INVALID,
                        "capability must be a simple identifier",
                    )
                }
                if (!result.add(raw)) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.MANIFEST_INVALID,
                        "duplicate capability",
                    )
                }
            }
            return result
        }

        private fun parseFiles(value: Any?): Map<String, String> {
            if (value == null) return emptyMap()
            if (value !is Map<*, *> || value.size > MAX_FILES || value.keys.any { it !is String }) {
                throw ExtensionManifestException(
                    XzPackValidationCode.MANIFEST_INVALID,
                    "files must be a small object",
                )
            }
            val result = linkedMapOf<String, String>()
            for (rawPath in value.keys.filterIsInstance<String>()) {
                val path = try {
                    XzPackPath.normalize(rawPath)
                } catch (error: XzPackPathException) {
                    throw ExtensionManifestException(
                        error.code,
                        "invalid declared file path",
                        error,
                    )
                }
                if (path == ExtensionManifest.FILE_NAME) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.MANIFEST_INVALID,
                        "manifest.json cannot declare its own digest",
                    )
                }
                val digest = value[rawPath]
                if (digest !is String || !DIGEST_PATTERN.matches(digest)) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.INVALID_FILE_DIGEST,
                        "declared file digest must be SHA-256",
                    )
                }
                if (result.put(path, digest.lowercase(Locale.ROOT)) != null) {
                    throw ExtensionManifestException(
                        XzPackValidationCode.DUPLICATE_DECLARED_FILE,
                        "duplicate declared file",
                    )
                }
            }
            return result
        }

        const val FILE_NAME = "manifest.json"
    }
}

class ExtensionManifestException(
    val code: XzPackValidationCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private class ManifestJsonException(message: String) : IllegalArgumentException(message)

/** Small strict JSON reader for the manifest shape; it rejects duplicate object keys. */
private class ManifestJsonParser(private val source: String) {
    private var index = 0
    private var depth = 0

    fun parseObject(): Map<String, Any?> {
        val result = parseValue()
        if (result !is Map<*, *> || result.keys.any { it !is String }) {
            throw ManifestJsonException("manifest root must be an object")
        }
        skipWhitespace()
        if (index != source.length) throw ManifestJsonException("trailing JSON content")
        return result.keys.filterIsInstance<String>().associateWith { result[it] }
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= source.length) throw ManifestJsonException("unexpected end of JSON")
        return when (source[index]) {
            '{' -> parseObjectValue()
            '[' -> parseArrayValue()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> throw ManifestJsonException("unexpected JSON token")
        }
    }

    private fun parseObjectValue(): Map<String, Any?> {
        enterContainer()
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, Any?>()
        if (consume('}')) {
            leaveContainer()
            return result
        }
        while (true) {
            skipWhitespace()
            if (index >= source.length || source[index] != '"') {
                throw ManifestJsonException("object key must be a string")
            }
            val key = parseString()
            if (result.containsKey(key)) throw ManifestJsonException("duplicate object key")
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        leaveContainer()
        return result
    }

    private fun parseArrayValue(): List<Any?> {
        enterContainer()
        expect('[')
        skipWhitespace()
        val result = mutableListOf<Any?>()
        if (consume(']')) {
            leaveContainer()
            return result
        }
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        leaveContainer()
        return result
    }

    private fun parseString(): String {
        expect('"')
        val output = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return output.toString()
                character.code < 0x20 -> throw ManifestJsonException("control character in string")
                character != '\\' -> output.append(character)
                index >= source.length -> throw ManifestJsonException("unterminated escape")
                else -> when (val escaped = source[index++]) {
                    '"', '\\', '/' -> output.append(escaped)
                    'b' -> output.append('\b')
                    'f' -> output.append('\u000c')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    'u' -> output.append(parseUnicodeEscape())
                    else -> throw ManifestJsonException("invalid string escape")
                }
            }
        }
        throw ManifestJsonException("unterminated string")
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > source.length) throw ManifestJsonException("short unicode escape")
        val hex = source.substring(index, index + 4)
        if (!hex.all { it in "0123456789abcdefABCDEF" }) {
            throw ManifestJsonException("invalid unicode escape")
        }
        index += 4
        return hex.toInt(16).toChar()
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!source.startsWith(literal, index)) throw ManifestJsonException("invalid JSON literal")
        index += literal.length
        return value
    }

    private fun parseNumber(): ManifestJsonNumber {
        val start = index
        if (consume('-')) Unit
        if (consume('0')) {
            if (index < source.length && source[index].isDigit()) {
                throw ManifestJsonException("leading zero in number")
            }
        } else {
            requireDigits()
        }
        if (consume('.')) {
            requireDigits()
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index += 1
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
            requireDigits()
        }
        return ManifestJsonNumber(source.substring(start, index))
    }

    private fun requireDigits() {
        val start = index
        while (index < source.length && source[index].isDigit()) index += 1
        if (start == index) throw ManifestJsonException("number requires digits")
    }

    private fun expect(expected: Char) {
        if (index >= source.length || source[index] != expected) {
            throw ManifestJsonException("expected $expected")
        }
        index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (index < source.length && source[index] == expected) {
            index += 1
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in " \\t\\r\\n") index += 1
    }

    private fun enterContainer() {
        depth += 1
        if (depth > 16) throw ManifestJsonException("manifest nesting is too deep")
    }

    private fun leaveContainer() {
        depth -= 1
    }
}

private data class ManifestJsonNumber(val raw: String)
