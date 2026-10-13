package mx.org.fraac.geocampo

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

object DbfParser {
    private data class Field(val name: String, val length: Int)

    fun parse(file: File?): List<Map<String, String>> {
        if (file == null || !file.exists()) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size < 32) return emptyList()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val records = bb.getInt(4)
        val headerLength = bb.getShort(8).toInt() and 0xFFFF
        val recordLength = bb.getShort(10).toInt() and 0xFFFF
        val fields = mutableListOf<Field>()
        var p = 32
        val charset = Charset.forName("windows-1252")
        while (p + 32 <= bytes.size && bytes[p].toInt() != 0x0D) {
            val nameBytes = bytes.copyOfRange(p, p + 11)
            val zero = nameBytes.indexOf(0)
            val name = String(if (zero >= 0) nameBytes.copyOfRange(0, zero) else nameBytes, charset).trim()
            val length = bytes[p + 16].toInt() and 0xFF
            if (name.isNotBlank() && length > 0) fields += Field(name, length)
            p += 32
        }
        val out = mutableListOf<Map<String, String>>()
        var offset = headerLength
        repeat(records) {
            if (offset + recordLength > bytes.size) return@repeat
            val deleted = bytes[offset].toInt().toChar() == '*'
            var cursor = offset + 1
            val props = linkedMapOf<String, String>()
            fields.forEach { field ->
                val end = (cursor + field.length).coerceAtMost(bytes.size)
                val value = String(bytes, cursor, end - cursor, charset).trim()
                if (value.isNotEmpty()) props[field.name] = value
                cursor += field.length
            }
            if (!deleted) out += props else out += emptyMap()
            offset += recordLength
        }
        return out
    }
}
