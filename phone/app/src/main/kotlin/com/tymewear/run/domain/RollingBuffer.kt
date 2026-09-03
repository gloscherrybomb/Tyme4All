package com.tymewear.run.domain

import java.util.LinkedList

/** Fixed-capacity rolling buffer for computing moving averages. */
class RollingBuffer(private val capacity: Int) {
    private val buf = LinkedList<Double>()
    fun add(value: Double) {
        buf.addLast(value)
        if (buf.size > capacity) buf.removeFirst()
    }
    fun average(): Double = if (buf.isEmpty()) 0.0 else buf.sum() / buf.size
    fun clear() { buf.clear() }
}
