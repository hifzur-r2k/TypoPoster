package com.typoposter.app.data

import org.json.JSONObject
import java.util.UUID

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Note",
    val content: String = "",
    val themeIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val isLocked: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("themeIndex", themeIndex)
        put("updatedAt", updatedAt)
        put("isLocked", isLocked)
    }

    companion object {
        fun fromJson(json: JSONObject): Note = Note(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "Note"),
            content = json.optString("content", ""),
            themeIndex = json.optInt("themeIndex", 0),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            isLocked = json.optBoolean("isLocked", false),
        )
    }
}
