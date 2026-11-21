package com.biandan.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * taskContent 复合字段编解码，对应 biandan_tools.py 的
 * decode_content / encode_content。
 *
 * 形态：JSON 字符串数组，首元素是备注或附件包
 * "[备注, [文件引用...]]" 的字符串化 JSON，其余元素为子任务标题。
 */
object TaskContent {

    data class Decoded(val remark: String, val subtasks: List<String>, val attachments: List<String>)

    fun decode(value: String?): Decoded {
        if (value == null) return Decoded("", emptyList(), emptyList())
        val outer = runCatching {
            BiandanCloud.json.parseToJsonElement(value) as? JsonArray
        }.getOrNull() ?: return Decoded(value, emptyList(), emptyList())
        if (outer.any { it !is JsonPrimitive || !it.jsonPrimitive.isString }) {
            return Decoded(value, emptyList(), emptyList())
        }
        val items = outer.map { it.jsonPrimitive.content }
        val remarkRaw = items.firstOrNull() ?: ""
        val subtasks = items.drop(1)
        val inner = runCatching {
            BiandanCloud.json.parseToJsonElement(remarkRaw) as? JsonArray
        }.getOrNull() ?: return Decoded(remarkRaw, subtasks, emptyList())
        if (inner.size == 2
            && (inner[0] as? JsonPrimitive)?.isString == true
            && inner[1] is JsonArray
            && (inner[1] as JsonArray).all { (it as? JsonPrimitive)?.isString == true }
        ) {
            return Decoded(
                inner[0].jsonPrimitive.content,
                subtasks,
                (inner[1] as JsonArray).map { it.jsonPrimitive.content },
            )
        }
        return Decoded(remarkRaw, subtasks, emptyList())
    }

    fun encode(remark: String, subtasks: List<String>, attachments: List<String> = emptyList()): String {
        val first = if (attachments.isEmpty()) {
            remark
        } else {
            buildJsonArray {
                add(JsonPrimitive(remark))
                add(buildJsonArray { attachments.forEach { add(JsonPrimitive(it)) } })
            }.toString()
        }
        return buildJsonArray {
            add(JsonPrimitive(first))
            subtasks.forEach { add(JsonPrimitive(it)) }
        }.toString()
    }
}
