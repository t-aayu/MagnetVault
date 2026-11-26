package com.example.data.firebase

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write")
}

/**
 * Classifies Firestore failures without logging or returning PII (UIDs, emails),
 * document IDs, queries, magnet URIs, or URLs.
 */
fun classifyFirestoreFailureCategory(exception: Throwable): String {
    val fsErr = generateSequence(exception) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    return when (fsErr?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "PERMISSION_DENIED"
        FirebaseFirestoreException.Code.UNAUTHENTICATED -> "UNAUTHENTICATED"
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> "NETWORK_UNAVAILABLE"
        FirebaseFirestoreException.Code.FAILED_PRECONDITION,
        FirebaseFirestoreException.Code.ABORTED -> "CONFLICT_RETRY"
        FirebaseFirestoreException.Code.INVALID_ARGUMENT -> "SCHEMA_REJECTED"
        else -> fsErr?.code?.name ?: exception.javaClass.simpleName
    }
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    collectionCategory: String?
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val errorCategory = classifyFirestoreFailureCategory(rootError)
    val safeCategory = sanitizeCollectionCategory(collectionCategory)
    val safeSummary = "op=${operationType.value}, category=$safeCategory, code=$errorCategory"
    Log.w("FirestoreSync", safeSummary)
    return errorCategory
}

private fun sanitizeCollectionCategory(raw: String?): String {
    val value = raw.orEmpty()
    return when {
        value.contains("bookmarks") -> "bookmarks"
        value.contains("search_history") -> "search_history"
        value.contains("mirror_configs") -> "mirror_configs"
        else -> "unknown"
    }
}
