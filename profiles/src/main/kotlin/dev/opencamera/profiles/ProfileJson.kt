/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON mapping for profiles/schema/device-profile.schema.json (schemaVersion 1).
 * Uses org.json, which Android provides at runtime.
 */
object ProfileJson {
    fun toJson(p: DeviceProfile): JSONObject = JSONObject().apply {
        put("schemaVersion", DeviceProfile.SCHEMA_VERSION)
        put("id", p.id)
        p.name?.let { put("name", it) }
        put("match", JSONObject().apply {
            put("brand", p.match.brand)
            put("device", JSONArray(p.match.device))
            put("model", JSONArray(p.match.model))
            if (p.match.fingerprintPrefix.isNotEmpty()) {
                put("fingerprintPrefix", JSONArray(p.match.fingerprintPrefix))
            }
            p.match.minSdk?.let { put("minSdk", it) }
            p.match.maxSdk?.let { put("maxSdk", it) }
        })
        if (p.cameras.isNotEmpty()) put("cameras", JSONArray(p.cameras.map { c ->
            JSONObject().apply {
                put("id", c.id)
                put("facing", c.facing.key)
                put("role", c.role.key)
                if (c.physicalIds.isNotEmpty()) put("physicalIds", JSONArray(c.physicalIds))
                put("focalMm", c.focalMm.json())
                put("equivFocalMm", c.equivFocalMm.json())
                put("sensorMm", JSONArray(listOf(c.sensorWidthMm.json(), c.sensorHeightMm.json())))
                put("pixelArray", JSONArray(listOf(c.pixelWidth, c.pixelHeight)))
                c.duplicateOf?.let { put("duplicateOf", it) }
                if (c.hidden) put("hidden", true)
            }
        }))
        if (p.zoomStops.isNotEmpty()) put("zoomStops", JSONArray(p.zoomStops.map { s ->
            JSONObject().apply {
                put("ratio", s.ratio.json())
                put("cameraId", s.cameraId)
                put("mode", s.mode.key)
                put("label", s.label)
            }
        }))
        if (p.lenses.isNotEmpty()) put("lenses", JSONArray(p.lenses.map { l ->
            JSONObject().apply {
                put("role", l.role.key)
                l.equivFocalMm?.let { put("equivFocalMm", it.json()) }
                l.ratio?.let {
                    put("ratio", it.json())
                    put("ratioOrigin", if (l.official) OFFICIAL_RATIO_ORIGIN else "derived")
                }
                val runtime = listOfNotNull(
                    "equivFocalMm".takeIf { l.equivFocalMm == null },
                    "ratio".takeIf { l.ratio == null && l.role in LensCatalog.STOP_ROLES },
                )
                if (runtime.isNotEmpty()) put("runtimeFields", JSONArray(runtime))
            }
        }))
        put("quirks", JSONArray(p.quirks.map { it.name }.sorted()))
        if (p.vendorTags.isNotEmpty()) put("vendorTags", JSONObject(p.vendorTags))
        put("knownBugs", JSONArray(p.knownBugs))
        put("source", p.source.key)
        put("testedBuilds", JSONArray(p.testedBuilds))
    }

    fun fromJson(o: JSONObject): DeviceProfile {
        require(o.getInt("schemaVersion") == DeviceProfile.SCHEMA_VERSION) { "Unsupported schemaVersion" }
        val m = o.getJSONObject("match")
        return DeviceProfile(
            id = o.getString("id"),
            name = o.optString("name").ifEmpty { null },
            match = ProfileMatch(
                brand = m.getString("brand"),
                device = m.optJSONArray("device").strings(),
                model = m.optJSONArray("model").strings(),
                fingerprintPrefix = m.optJSONArray("fingerprintPrefix").strings(),
                minSdk = if (m.has("minSdk")) m.getInt("minSdk") else null,
                maxSdk = if (m.has("maxSdk")) m.getInt("maxSdk") else null,
            ),
            cameras = o.optJSONArray("cameras").objects().map { c ->
                val sensor = c.getJSONArray("sensorMm")
                val pixels = c.optJSONArray("pixelArray")
                CameraEntry(
                    id = c.getString("id"),
                    facing = Facing.fromKey(c.getString("facing")),
                    role = LensRole.fromKey(c.getString("role")),
                    focalMm = c.getDouble("focalMm").toFloat(),
                    equivFocalMm = c.getDouble("equivFocalMm").toFloat(),
                    sensorWidthMm = sensor.getDouble(0).toFloat(),
                    sensorHeightMm = sensor.getDouble(1).toFloat(),
                    pixelWidth = pixels?.getInt(0) ?: 0,
                    pixelHeight = pixels?.getInt(1) ?: 0,
                    physicalIds = c.optJSONArray("physicalIds").strings(),
                    duplicateOf = if (c.has("duplicateOf")) c.getString("duplicateOf") else null,
                    hidden = c.optBoolean("hidden", false),
                )
            },
            zoomStops = o.optJSONArray("zoomStops").objects().map { s ->
                ZoomStop(
                    ratio = s.getDouble("ratio").toFloat(),
                    cameraId = s.getString("cameraId"),
                    mode = StopMode.fromKey(s.getString("mode")),
                    label = s.getString("label"),
                )
            },
            lenses = o.optJSONArray("lenses").objects().map { l ->
                CatalogLens(
                    role = LensRole.fromKey(l.getString("role")),
                    equivFocalMm = if (l.has("equivFocalMm")) l.getDouble("equivFocalMm").toFloat() else null,
                    ratio = if (l.has("ratio")) l.getDouble("ratio").toFloat() else null,
                    official = l.optString("ratioOrigin").startsWith("official") ||
                        l.optString("ratioOrigin") == "display_convention",
                )
            },
            quirks = o.optJSONArray("quirks").strings().mapNotNull(Quirk::fromKey).toSet(),
            source = ProfileSource.fromKey(o.getString("source")),
            testedBuilds = o.optJSONArray("testedBuilds").strings(),
            knownBugs = o.optJSONArray("knownBugs").strings(),
            vendorTags = o.optJSONObject("vendorTags")?.let { tags ->
                tags.keys().asSequence().associateWith { tags.getString(it) }
            } ?: emptyMap(),
        )
    }

    /** Four decimals: enough for millimetres, avoids float-to-double noise in exports. */
    private fun Float.json() = Math.round(this * 10_000.0) / 10_000.0

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private const val OFFICIAL_RATIO_ORIGIN = "official_ui_stop"
}