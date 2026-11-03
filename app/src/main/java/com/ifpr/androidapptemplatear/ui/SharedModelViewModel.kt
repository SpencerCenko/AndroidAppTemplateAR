package com.ifpr.androidapptemplatear.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedModelViewModel : ViewModel() {

    private val _selectedModel = MutableLiveData<String>()
    val selectedModel: LiveData<String> = _selectedModel

    fun selectModel(path: String) {
        _selectedModel.value = path
    }
}