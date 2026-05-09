package org.linphone.incomingcall.bot

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement

private val gsonPretty = GsonBuilder().setPrettyPrinting().create()

fun JsonElement.prettyJson(): String = gsonPretty.toJson(this)
