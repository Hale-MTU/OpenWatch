package tech.mmarca.openwatch.healthconnect

/**
 * The most records one insert carries. Health Connect asks for no more than a thousand a
 * request; a watch's first sync brings several thousand minutes at once.
 */
const val MaxRecordsPerInsert = 1000
