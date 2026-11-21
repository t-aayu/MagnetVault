package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.CloudSyncRepository
import com.example.model.BookmarkRecord
import com.example.model.SearchHistoryRecord
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VaultViewModel(
    private val cloudSyncRepo: CloudSyncRepository
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val bookmarksState: StateFlow<UiState<List<BookmarkRecord>>> =
        cloudSyncRepo.observeBookmarks()
            .map<List<BookmarkRecord>, UiState<List<BookmarkRecord>>> { UiState.Success(it) }
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                val root = fsErr ?: error
                emit(UiState.Error(root.message ?: "Failed to sync bookmarks"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    val historyState: StateFlow<UiState<List<SearchHistoryRecord>>> =
        cloudSyncRepo.observeSearchHistory()
            .map<List<SearchHistoryRecord>, UiState<List<SearchHistoryRecord>>> { UiState.Success(it) }
            .catch { error ->
                val fsErr = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (fsErr == null && error is CancellationException) throw error
                val root = fsErr ?: error
                emit(UiState.Error(root.message ?: "Failed to sync search history"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    fun removeBookmark(bookmarkId: String) {
        viewModelScope.launch {
            cloudSyncRepo.removeBookmark(bookmarkId).onSuccess {
                _messages.emit("Bookmark removed")
            }.onFailure { err ->
                _messages.emit(err.localizedMessage ?: "Failed to remove bookmark")
            }
        }
    }

    fun deleteHistoryEntry(entryId: String) {
        viewModelScope.launch {
            cloudSyncRepo.deleteSearchHistoryEntry(entryId).onFailure { err ->
                _messages.emit(err.localizedMessage ?: "Failed to delete history entry")
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            cloudSyncRepo.clearAllSearchHistory().onSuccess {
                _messages.emit("Search history cleared")
            }.onFailure { err ->
                _messages.emit(err.localizedMessage ?: "Failed to clear history")
            }
        }
    }

    class Factory(
        private val cloudSyncRepo: CloudSyncRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            VaultViewModel(cloudSyncRepo) as T
    }
}
