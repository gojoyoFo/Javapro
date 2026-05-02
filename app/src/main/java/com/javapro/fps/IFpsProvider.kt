package com.javapro.fps

interface IFpsProvider {
    fun getInstantFps(): Float
    fun release() {}
}
