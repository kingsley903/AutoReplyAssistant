package com.example.autoreplyassistant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActivityLogItem(
    val time: String,
    val status: String,
    val details: String
)

object ActivityLogStore {
    private const val MAX_ITEMS = 100
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mutableItems = MutableStateFlow<List<ActivityLogItem>>(emptyList())
    val items: StateFlow<List<ActivityLogItem>> = mutableItems.asStateFlow()

    @Synchronized
    fun add(status: String, details: String) {
        val item = ActivityLogItem(formatter.format(Date()), status, details.take(500))
        mutableItems.value = (listOf(item) + mutableItems.value).take(MAX_ITEMS)
    }

    @Synchronized
    fun clearLogs() {
        mutableItems.value = emptyList()
    }
}
