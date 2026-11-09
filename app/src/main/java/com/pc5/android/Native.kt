package com.pc5.android

object Native {
    init { System.loadLibrary("pc5") }
    external fun key(): ByteArray
    external fun url(): String
    external fun cert(): String
    external fun traced(): Boolean
}
