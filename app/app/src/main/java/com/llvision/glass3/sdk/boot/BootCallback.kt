package com.llvision.glass3.sdk.boot

interface BootCallback {
    fun onFail(code: Int, msg: String?)
    fun onSuccess()
}
