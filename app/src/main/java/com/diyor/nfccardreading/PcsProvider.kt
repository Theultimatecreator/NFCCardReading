package com.diyor.nfccardreading

import android.nfc.tech.IsoDep
import com.github.devnied.emvnfccard.exception.CommunicationException
import com.github.devnied.emvnfccard.parser.IProvider
import java.io.IOException

/**
 * Implementation of Provider for NFC card communication
 */
class PcscProvider : IProvider {

    private var mTagCom: IsoDep? = null

    fun setmTagCom(mTagCom: IsoDep?) {
        this.mTagCom = mTagCom
    }

    @Throws(CommunicationException::class)
    override fun transceive(command: ByteArray): ByteArray {
        try {
            mTagCom?.let {
                if (!it.isConnected) {
                    try {
                        it.connect()
                    } catch (e: IOException) {
                        throw CommunicationException(e.message)
                    }
                }

                // Transmit command to the card
                return it.transceive(command)
            }

            // If mTagCom is null, throw exception
            throw CommunicationException("NFC Tag communication not initialized")
        } catch (e: IOException) {
            throw CommunicationException(e.message)
        }
    }

    override fun getAt(): ByteArray? {
        return null // Answer To Reset not supported for contactless cards
    }
}