package com.simplereader.util

// Event wrapper
// Simple LiveData event wrapper for one-time events
// To use:  private val _myVal = MutableLiveData<ReaderEvent<myType>>()
//          val myVal: LiveData<Event<myType>> = _myVal
//
//          viewModel.myVal.observe(viewLifecycleOwner) { event ->
//               event.getContentIfNotHandled()?.let { myType ->
//                  do something...
//              }
//          }
open class ReaderEvent<out T>(private val content: T) {

    private var hasBeenHandled = false

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) null else {
            hasBeenHandled = true
            content
        }
    }

    fun peekContent(): T = content
}