package com.premierdarkcoffee.nexo.connect.lab.domain.push

class PushTokenSecret private constructor(bytes: ByteArray) : AutoCloseable {
    private val lock = Any()
    private var material: ByteArray? = bytes.copyOf()

    init {
        require(bytes.size in MIN_TOKEN_BYTES..MAX_TOKEN_BYTES) {
            "A push token must contain between $MIN_TOKEN_BYTES and $MAX_TOKEN_BYTES bytes"
        }
    }

    internal fun <T> withBytes(block: (ByteArray) -> T): T = synchronized(lock) {
        val current = checkNotNull(material) { "Push token secret is closed" }
        val workingCopy = current.copyOf()
        try {
            block(workingCopy)
        } finally {
            workingCopy.fill(0)
        }
    }

    override fun close() {
        synchronized(lock) {
            material?.fill(0)
            material = null
        }
    }

    override fun toString(): String = "PushTokenSecret([REDACTED])"

    companion object {
        private const val MIN_TOKEN_BYTES = 16
        private const val MAX_TOKEN_BYTES = 4096

        fun fromBytes(bytes: ByteArray): PushTokenSecret = PushTokenSecret(bytes)
    }
}
