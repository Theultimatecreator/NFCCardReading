package com.diyor.nfccardreading


import android.app.AlertDialog
import android.content.Context.VIBRATOR_SERVICE
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.github.devnied.emvnfccard.parser.EmvTemplate
import java.io.IOException
import java.time.ZoneId

class MainActivity : FragmentActivity(), NfcAdapter.ReaderCallback {
    private var mNfcAdapter: NfcAdapter? = null
    private val TAG = "NfcCardReader"

    private val cardNumber = mutableStateOf<String?>(null)
    private val expiryDate = mutableStateOf<String?>(null)
    private val statusMessage = mutableStateOf("Please place your card on the back of the device")
    private val isReading = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize NFC adapter
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Check if device supports NFC
        if (mNfcAdapter == null) {
            showError("This device doesn't support NFC")
            return
        }

        setContent {
            CardReaderScreen()
        }
    }

    @Composable
    fun CardReaderScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = statusMessage.value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (cardNumber.value != null) {
                Text(
                    text = "Card Number: ${cardNumber.value}",
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Expiry Date: ${expiryDate.value}",
                    fontSize = 16.sp
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (mNfcAdapter == null) return

        // Check if NFC is enabled
        if (!mNfcAdapter!!.isEnabled) {
            showNfcDisabledDialog()
            return
        }

        // Enable NFC reader mode
        try {
            val options = Bundle()
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            mNfcAdapter!!.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V,
                options
            )
            statusMessage.value = "Ready to scan. Place card on back of device."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable NFC reader mode", e)
            showError("Failed to enable NFC reader: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()

        // Disable reader mode when app is paused
        if (mNfcAdapter != null) {
            try {
                mNfcAdapter!!.disableReaderMode(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable NFC reader mode", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onTagDiscovered(tag: Tag?) {
        isReading.value = true
        statusMessage.value = "Reading card... Please don't move the card"

        var isoDep: IsoDep? = null

        try {
            Log.d(TAG, "Tag discovered: $tag")

            // Get IsoDep instance from the tag
            isoDep = IsoDep.get(tag)

            if (isoDep == null) {
                updateUIOnError("Card type not supported")
                return
            }

            // Provide haptic feedback on card detection
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
                VibrationEffect.createOneShot(150, 10)
            )

            // Connect to the card
            isoDep.connect()
            Log.d(TAG, "Connected to card")

            // Set timeout for card operations (5 seconds)
            isoDep.timeout = 5000

            // Initialize provider with the tag communication
            val provider = PcscProvider()
            provider.setmTagCom(isoDep)

            // Configure EMV parser
            val config = EmvTemplate.Config()
                .setContactLess(true)
                .setReadAllAids(true)
                .setReadTransactions(true)
                .setRemoveDefaultParsers(false)
                .setReadAt(true)

            val parser = EmvTemplate.Builder()
                .setProvider(provider)
                .setConfig(config)
                .build()

            // Read card data
            Log.d(TAG, "Reading EMV card...")
            val card = parser.readEmvCard()

            // Process card data
            val number = card.cardNumber
            val expireDate = card.expireDate

            if (number.isNullOrEmpty()) {
                updateUIOnError("Could not read card number")
                return
            }

            var formattedDate = "Unknown"
            if (expireDate != null) {
                val date = expireDate.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                formattedDate = "${date.monthValue}/${date.year % 100}"
            }

            // Update UI with card information
            updateUIWithCardInfo(number, formattedDate)

        } catch (e: IOException) {
            Log.e(TAG, "IO error while reading card", e)
            updateUIOnError("Communication error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading card", e)
            updateUIOnError("Failed to read card: ${e.message}")
        } finally {
            // Always close the connection
            try {
                isoDep?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing card connection", e)
            }

            isReading.value = false
        }
    }

    private fun updateUIWithCardInfo(cardNum: String, expiry: String) {
        runOnUiThread {
            cardNumber.value = formatCardNumber(cardNum)
            expiryDate.value = expiry
            statusMessage.value = "Card read successfully!"

            // You can also handle successfully reading the card here
            // For example, navigate to a different screen or save the data
        }
    }

    private fun updateUIOnError(message: String) {
        runOnUiThread {
            statusMessage.value = "Error: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatCardNumber(number: String): String {
        val result = StringBuilder()
        for (i in number.indices) {
            // Add space after every 4 digits
            if (i > 0 && i % 4 == 0) {
                result.append(" ")
            }
            result.append(number[i])
        }
        return result.toString()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        statusMessage.value = "Error: $message"
    }

    private fun showNfcDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("NFC is Disabled")
            .setMessage("NFC is required but currently disabled. Would you like to enable it?")
            .setPositiveButton("Enable NFC") { _, _ ->
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                statusMessage.value = "NFC is disabled. Enable it to read cards."
            }
            .setCancelable(false)
            .show()
    }
}
