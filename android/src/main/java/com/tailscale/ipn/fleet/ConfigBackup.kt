// Victorious Mesh
// Unified fleet SETTINGS/BACKUP (droidcommand-AI UNIFIED_BACKUP.md / DCA #236).
// JVM-testable: no Android imports. Export/import never carry secrets.

package com.tailscale.ipn.fleet

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class BackupWrapping {
  NONE,
  RAW_AES_256_GCM,
  ANDROID_KEYSTORE;

  val wire: String
    get() =
        when (this) {
          NONE -> "none"
          RAW_AES_256_GCM -> "raw-aes-256-gcm"
          ANDROID_KEYSTORE -> "android-keystore"
        }

  companion object {
    fun fromWire(raw: String): BackupWrapping =
        when (raw) {
          "none" -> NONE
          "raw-aes-256-gcm" -> RAW_AES_256_GCM
          "android-keystore" -> ANDROID_KEYSTORE
          else ->
              throw InvalidParametersException("unknown wrapping $raw")
        }
  }
}

enum class BackupConflictPolicy {
  SKIP,
  OVERWRITE,
  MERGE_BY_ID;

  val wire: String
    get() =
        when (this) {
          SKIP -> "skip"
          OVERWRITE -> "overwrite"
          MERGE_BY_ID -> "merge-by-id"
        }

  companion object {
    fun fromWire(raw: String?): BackupConflictPolicy =
        when (raw?.trim()?.lowercase().orEmpty()) {
          "",
          "skip" -> SKIP
          "overwrite" -> OVERWRITE
          "merge-by-id" -> MERGE_BY_ID
          else -> throw InvalidParametersException("conflictPolicy must be skip, overwrite, or merge-by-id")
        }
  }
}

data class UnifiedBackupEntry(
    val packageName: String,
    val schemaVersion: Int,
    val ciphertext: String? = null,
    val clearPayload: String? = null,
)

data class UnifiedFleetBackup(
    val version: Int,
    val createdAtMs: Long,
    val sourceNodeRef: String,
    val wrapping: BackupWrapping,
    val entries: List<UnifiedBackupEntry>,
)

@Serializable
data class WireGuardProfileMeta(val id: String, val name: String)

@Serializable
data class MeshNonSecretPrefs(
    val corpDns: Boolean? = null,
    val wantRunning: Boolean? = null,
    val exitNodeAllowLanAccess: Boolean? = null,
)

/** This node's schemaVersion 1 payload. Never includes keys, tokens, or passwords. */
@Serializable
data class MeshSettingsPayload(
    val controlUrl: String? = null,
    val tunnelMode: String? = null,
    val activeWireGuardProfileId: String? = null,
    val prefs: MeshNonSecretPrefs? = null,
    val wireGuardProfiles: List<WireGuardProfileMeta> = emptyList(),
)

data class BackupChange(
    val path: String,
    val action: String,
    val live: String? = null,
    val imported: String? = null,
)

data class ImportPlan(
    val dryRun: Boolean,
    val conflictPolicy: BackupConflictPolicy,
    val changes: List<BackupChange>,
    val applied: MeshSettingsPayload,
)

interface ConfigStore {
  fun snapshot(): MeshSettingsPayload

  fun apply(payload: MeshSettingsPayload)

  fun isExternalControlEnabled(): Boolean

  fun setExternalControlEnabled(enabled: Boolean)
}

/** In-memory store for JVM tests and as the dispatcher default. */
class MemoryConfigStore(
    var settings: MeshSettingsPayload = MeshSettingsPayload(),
    private var controlEnabled: Boolean = false,
) : ConfigStore {
  override fun snapshot(): MeshSettingsPayload = settings

  override fun apply(payload: MeshSettingsPayload) {
    settings = payload
  }

  override fun isExternalControlEnabled(): Boolean = controlEnabled

  override fun setExternalControlEnabled(enabled: Boolean) {
    controlEnabled = enabled
  }
}

object MeshBackup {
  const val PACKAGE_NAME = NodeStatusSnapshot.PACKAGE_VICTORIOUS_MESH
  const val SCHEMA_VERSION = 1
  const val WRAPPING_NONE = "none"

  val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
}

/** Reads backup args from `parameters.args` or the parameters object itself. Unknown keys ignored. */
object BackupArgs {
  fun string(params: JsonObject, key: String): String? {
    val args = params["args"] as? JsonObject
    return content(args?.get(key)) ?: content(params[key])
  }

  fun bool(params: JsonObject, key: String): Boolean {
    val args = params["args"] as? JsonObject
    return asBool(args?.get(key)) || asBool(params[key])
  }

  fun jsonObject(params: JsonObject, key: String): JsonObject? {
    val args = params["args"] as? JsonObject
    return (args?.get(key) as? JsonObject) ?: (params[key] as? JsonObject)
  }

  private fun content(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

  private fun asBool(el: JsonElement?): Boolean {
    val p = el as? JsonPrimitive ?: return false
    if (p.booleanOrNull == true) return true
    return p.contentOrNull.equals("true", ignoreCase = true)
  }
}

/**
 * Refuse (do not strip) if a payload key or string value looks like a secret.
 * Names from UNIFIED_BACKUP.md plus mesh-specific key material.
 */
object BackupSecretScanner {
  private val FORBIDDEN =
      Regex(
          """(?i)(?<![A-Za-z0-9_])(""" +
              """password|passwd|passphrase|""" +
              """api[_-]?key|api[_-]?token|bearer|(?<![A-Za-z])token(?![A-Za-z])|""" +
              """secret|signing[_-]?key|private[_-]?key|""" +
              """preshared([_-]?key)?|(?<![A-Za-z])psk(?![A-Za-z])|""" +
              """auth[_-]?key|auth[_-]?token|node[_-]?key|""" +
              """cookie|keystore|-----begin|""" +
              """twilio|number[_-]?pool|code[_-]?word|whitelist|""" +
              """headscale[_-]?auth|tailscale[_-]?auth""" +
              """)(?![A-Za-z0-9_])"""
      )

  fun refuseIfSecret(element: JsonElement, label: String = "payload") {
    walk(element, label)
  }

  fun refuseIfSecretText(text: String, label: String = "payload") {
    if (FORBIDDEN.containsMatchIn(text)) {
      throw InvalidParametersException("$label contains a forbidden secret name")
    }
  }

  private fun walk(element: JsonElement, label: String) {
    when (element) {
      is JsonNull -> Unit
      is JsonPrimitive -> {
        if (element.isString) refuseIfSecretText(element.content, label)
      }
      is JsonArray -> element.forEach { walk(it, label) }
      is JsonObject -> {
        for ((key, value) in element) {
          refuseIfSecretText(key, label)
          walk(value, label)
        }
      }
    }
  }
}

object UnifiedBackupCodec {
  fun encodeEntry(entry: UnifiedBackupEntry): JsonObject {
    validateEntry(entry)
    BackupSecretScanner.refuseIfSecretText(entry.packageName, "packageName")
    entry.clearPayload?.let { scanClearPayload(it) }
    return buildJsonObject {
      put("packageName", entry.packageName)
      put("schemaVersion", entry.schemaVersion)
      val cipher = entry.ciphertext
      val clear = entry.clearPayload
      if (cipher != null) put("ciphertext", cipher)
      if (clear != null) put("clearPayload", clear)
    }
  }

  fun decodeEntry(obj: JsonObject): UnifiedBackupEntry {
    val packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (packageName.isBlank()) throw InvalidParametersException("packageName is required")
    val schemaVersion =
        obj["schemaVersion"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: throw InvalidParametersException("schemaVersion is required")
    val ciphertext = obj["ciphertext"]?.jsonPrimitive?.contentOrNull
    val clearPayload = obj["clearPayload"]?.jsonPrimitive?.contentOrNull
    val entry = UnifiedBackupEntry(packageName, schemaVersion, ciphertext, clearPayload)
    validateEntry(entry)
    BackupSecretScanner.refuseIfSecretText(packageName, "packageName")
    clearPayload?.let { scanClearPayload(it) }
    return entry
  }

  fun encodeBundle(bundle: UnifiedFleetBackup): JsonObject {
    validateBundle(bundle)
    return buildJsonObject {
      put("version", bundle.version)
      put("createdAtMs", bundle.createdAtMs)
      put("sourceNodeRef", bundle.sourceNodeRef)
      put("wrapping", bundle.wrapping.wire)
      putJsonArray("entries") { bundle.entries.forEach { add(encodeEntry(it)) } }
    }
  }

  fun decodeBundle(raw: String): UnifiedFleetBackup {
    val el =
        try {
          MeshBackup.json.parseToJsonElement(raw)
        } catch (_: Exception) {
          throw InvalidParametersException("bundle is not valid JSON")
        }
    if (el !is JsonObject) throw InvalidParametersException("bundle must be a JSON object")
    return decodeBundle(el)
  }

  fun decodeBundle(obj: JsonObject): UnifiedFleetBackup {
    val version =
        obj["version"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: throw InvalidParametersException("bundle version is required")
    if (version != 1) throw InvalidParametersException("unknown bundle version $version")
    val createdAtMs =
        obj["createdAtMs"]?.jsonPrimitive?.longOrNull
            ?: throw InvalidParametersException("createdAtMs is required")
    val sourceNodeRef = obj["sourceNodeRef"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (sourceNodeRef.isBlank()) throw InvalidParametersException("sourceNodeRef is required")
    val wrappingRaw = obj["wrapping"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (wrappingRaw.isBlank()) throw InvalidParametersException("wrapping is required")
    val wrapping = BackupWrapping.fromWire(wrappingRaw)
    val entriesEl = obj["entries"] as? JsonArray ?: throw InvalidParametersException("entries is required")
    val entries = entriesEl.map { decodeEntry(it.jsonObject) }
    val bundle = UnifiedFleetBackup(version, createdAtMs, sourceNodeRef, wrapping, entries)
    validateBundle(bundle)
    return bundle
  }

  fun encodePayload(payload: MeshSettingsPayload): String {
    val json = MeshBackup.json.encodeToString(MeshSettingsPayload.serializer(), payload)
    scanClearPayload(json)
    return json
  }

  fun decodePayload(raw: String): MeshSettingsPayload {
    scanClearPayload(raw)
    return try {
      MeshBackup.json.decodeFromString(MeshSettingsPayload.serializer(), raw)
    } catch (e: InvalidParametersException) {
      throw e
    } catch (_: Exception) {
      throw InvalidParametersException("clearPayload is not a valid mesh settings object")
    }
  }

  fun thisNodeEntry(payload: MeshSettingsPayload, packageName: String = MeshBackup.PACKAGE_NAME): UnifiedBackupEntry {
    val clear = encodePayload(payload)
    return UnifiedBackupEntry(
        packageName = packageName,
        schemaVersion = MeshBackup.SCHEMA_VERSION,
        clearPayload = clear,
    )
  }

  fun entryForPackage(bundle: UnifiedFleetBackup, packageName: String): UnifiedBackupEntry {
    val matches = bundle.entries.filter { it.packageName == packageName }
    if (matches.isEmpty()) throw ActionNotFound("named packageName has no entry")
    if (matches.size > 1) throw InvalidParametersException("duplicate packageName $packageName")
    return matches[0]
  }

  fun unwrapClearPayload(bundle: UnifiedFleetBackup, entry: UnifiedBackupEntry): String {
    if (bundle.wrapping != BackupWrapping.NONE) {
      throw InvalidParametersException("wrapping ${bundle.wrapping.wire} is not supported on this node")
    }
    return entry.clearPayload
        ?: throw InvalidParametersException("wrapping=none requires clearPayload")
  }

  private fun scanClearPayload(raw: String) {
    BackupSecretScanner.refuseIfSecretText(raw, "payload")
    if (FleetRedaction.containsMeshAddress(raw) || FleetRedaction.jsonContainsBackupForbiddenKeys(raw)) {
      throw InvalidParametersException("payload must not include mesh IP addresses")
    }
    val el =
        try {
          MeshBackup.json.parseToJsonElement(raw)
        } catch (_: Exception) {
          throw InvalidParametersException("clearPayload is not valid JSON")
        }
    BackupSecretScanner.refuseIfSecret(el, "payload")
  }

  private fun validateEntry(entry: UnifiedBackupEntry) {
    if (entry.packageName.isBlank()) throw InvalidParametersException("packageName is required")
    val hasCipher = !entry.ciphertext.isNullOrBlank()
    val hasClear = !entry.clearPayload.isNullOrBlank()
    if (hasCipher == hasClear) {
      throw InvalidParametersException("exactly one of ciphertext or clearPayload is required")
    }
  }

  private fun validateBundle(bundle: UnifiedFleetBackup) {
    if (bundle.version != 1) throw InvalidParametersException("unknown bundle version ${bundle.version}")
    if (bundle.sourceNodeRef.isBlank()) throw InvalidParametersException("sourceNodeRef is required")
    val seen = HashSet<String>()
    for (entry in bundle.entries) {
      if (!seen.add(entry.packageName)) {
        throw InvalidParametersException("duplicate packageName ${entry.packageName}")
      }
      when (bundle.wrapping) {
        BackupWrapping.NONE ->
            if (entry.clearPayload.isNullOrBlank() || !entry.ciphertext.isNullOrBlank()) {
              throw InvalidParametersException("wrapping=none requires clearPayload")
            }
        BackupWrapping.RAW_AES_256_GCM,
        BackupWrapping.ANDROID_KEYSTORE ->
            throw InvalidParametersException("wrapping ${bundle.wrapping.wire} is not supported on this node")
      }
    }
  }
}

object BackupImporter {
  fun plan(
      live: MeshSettingsPayload,
      imported: MeshSettingsPayload,
      policy: BackupConflictPolicy,
      dryRun: Boolean,
  ): ImportPlan {
    val changes = ArrayList<BackupChange>()
    val controlUrl = pickScalar("controlUrl", live.controlUrl, imported.controlUrl, policy, changes)
    val tunnelMode = pickScalar("tunnelMode", live.tunnelMode, imported.tunnelMode, policy, changes)
    val activeId =
        pickScalar(
            "activeWireGuardProfileId",
            live.activeWireGuardProfileId,
            imported.activeWireGuardProfileId,
            policy,
            changes,
        )
    val livePrefs = live.prefs ?: MeshNonSecretPrefs()
    val importedPrefs = imported.prefs ?: MeshNonSecretPrefs()
    val prefs =
        MeshNonSecretPrefs(
            corpDns = pickScalar("prefs.corpDns", livePrefs.corpDns, importedPrefs.corpDns, policy, changes),
            wantRunning =
                pickScalar("prefs.wantRunning", livePrefs.wantRunning, importedPrefs.wantRunning, policy, changes),
            exitNodeAllowLanAccess =
                pickScalar(
                    "prefs.exitNodeAllowLanAccess",
                    livePrefs.exitNodeAllowLanAccess,
                    importedPrefs.exitNodeAllowLanAccess,
                    policy,
                    changes,
                ),
        )
    val profiles = mergeProfiles(live.wireGuardProfiles, imported.wireGuardProfiles, policy, changes)
    return ImportPlan(
        dryRun = dryRun,
        conflictPolicy = policy,
        changes = changes,
        applied =
            MeshSettingsPayload(
                controlUrl = controlUrl,
                tunnelMode = tunnelMode,
                activeWireGuardProfileId = activeId,
                prefs = prefs,
                wireGuardProfiles = profiles,
            ),
    )
  }

  fun apply(store: ConfigStore, plan: ImportPlan) {
    store.apply(plan.applied)
    store.setExternalControlEnabled(false)
  }

  fun encodeResult(plan: ImportPlan): JsonObject =
      buildJsonObject {
        put("dryRun", plan.dryRun)
        put("conflictPolicy", plan.conflictPolicy.wire)
        if (plan.dryRun) put("wouldWrite", false) else put("applied", true)
        put("externalControlEnabled", false)
        putJsonArray("changes") {
          plan.changes.forEach { change ->
            add(
                buildJsonObject {
                  put("path", change.path)
                  put("action", change.action)
                  putNullable("live", change.live)
                  putNullable("imported", change.imported)
                }
            )
          }
        }
      }

  private fun mergeProfiles(
      live: List<WireGuardProfileMeta>,
      imported: List<WireGuardProfileMeta>,
      policy: BackupConflictPolicy,
      changes: MutableList<BackupChange>,
  ): List<WireGuardProfileMeta> {
    val liveById = LinkedHashMap<String, WireGuardProfileMeta>()
    live.forEach { if (it.id.isNotBlank()) liveById[it.id] = it }
    val out = LinkedHashMap<String, WireGuardProfileMeta>(liveById)
    for (row in imported) {
      if (row.id.isBlank()) continue
      val path = "wireGuardProfiles[${row.id}]"
      val existing = liveById[row.id]
      when (policy) {
        BackupConflictPolicy.SKIP -> {
          changes.add(BackupChange(path, if (existing == null) "skip" else "keep", existing?.name, row.name))
        }
        BackupConflictPolicy.OVERWRITE -> {
          out[row.id] = row
          changes.add(
              BackupChange(path, if (existing == null) "add" else "overwrite", existing?.name, row.name)
          )
        }
        BackupConflictPolicy.MERGE_BY_ID -> {
          if (existing == null) {
            out[row.id] = row
            changes.add(BackupChange(path, "add", null, row.name))
          } else {
            changes.add(BackupChange(path, "keep", existing.name, row.name))
          }
        }
      }
    }
    return out.values.toList()
  }

  private fun <T> pickScalar(
      path: String,
      live: T?,
      imported: T?,
      policy: BackupConflictPolicy,
      changes: MutableList<BackupChange>,
  ): T? {
    val liveEmpty = isEmpty(live)
    val importedEmpty = isEmpty(imported)
    val chosen: T? =
        when (policy) {
          BackupConflictPolicy.SKIP -> live
          BackupConflictPolicy.OVERWRITE -> if (!importedEmpty) imported else live
          BackupConflictPolicy.MERGE_BY_ID -> if (liveEmpty) imported else live
        }
    val action =
        when {
          importedEmpty -> "keep"
          policy == BackupConflictPolicy.SKIP && !liveEmpty -> "skip"
          chosen == imported && live != imported -> if (liveEmpty) "add" else "overwrite"
          else -> "keep"
        }
    if (!importedEmpty || !liveEmpty) {
      changes.add(BackupChange(path, action, live?.toString(), imported?.toString()))
    }
    return chosen
  }

  private fun isEmpty(value: Any?): Boolean =
      when (value) {
        null -> true
        is String -> value.isBlank()
        else -> false
      }

  private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
  }
}
