package com.brr.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Accès aux cas de test partagés avec le web (design/fixtures). */
object Fixtures {
    private val dir = File(System.getProperty("fixtures") ?: "../../design/fixtures")
    fun obj(name: String) = JSONObject(File(dir, name).readText())
    fun arr(name: String) = JSONArray(File(dir, name).readText())
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k)
