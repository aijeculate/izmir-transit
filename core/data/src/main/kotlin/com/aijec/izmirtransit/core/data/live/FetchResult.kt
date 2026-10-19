package com.aijec.izmirtransit.core.data.live

import com.aijec.izmirtransit.core.model.LiveError

sealed interface FetchResult<out T> {
    data class Success<T>(val value: T, val skippedRecords: Int = 0) : FetchResult<T>

    data class Failure(val error: LiveError, val retryable: Boolean, val detail: String? = null) : FetchResult<Nothing>
}

inline fun <T, R> FetchResult<T>.map(transform: (T) -> R): FetchResult<R> = when (this) {
    is FetchResult.Success -> FetchResult.Success(transform(value), skippedRecords)
    is FetchResult.Failure -> this
}
