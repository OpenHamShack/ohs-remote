package com.openhamshack.kind.stationflow

import org.json.JSONObject

internal fun fixture(name: String): JSONObject =
    JSONObject(Fixtures::class.java.classLoader!!.getResource(name)!!.readText())

private object Fixtures
