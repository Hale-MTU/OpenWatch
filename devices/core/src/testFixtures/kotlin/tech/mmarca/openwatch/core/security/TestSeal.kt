package tech.mmarca.openwatch.core.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import tech.mmarca.openwatch.wearlink.SecretSeal

/** One software key for the whole test run, standing in for the Keystore's: a restarted store opens what an earlier one sealed. */
private val TestKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

fun testSeal(): SecretSeal = SecretSeal { TestKey }

/** A phone whose Keystore key is gone: every sealed secret stays shut. */
fun lostKeySeal(): SecretSeal = SecretSeal { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
