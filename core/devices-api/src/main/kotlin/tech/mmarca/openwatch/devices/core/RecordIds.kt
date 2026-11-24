package tech.mmarca.openwatch.devices.core

/**
 * A record id for one device of a family: [id] as it always was for the family's first device,
 * the empty [salt], and `id@salt` for each device after it, so two watches of one family do not
 * overwrite each other's records. No natural key holds an `@`, so a salted id never equals
 * another device's.
 */
fun saltedRecordId(id: String, salt: String): String = if (salt.isEmpty()) id else "$id@$salt"

/** The salt [id] was made with: empty for a family's first device, and for an id from before salts. */
fun recordIdSalt(id: String?): String = id?.substringAfter('@', "").orEmpty()
