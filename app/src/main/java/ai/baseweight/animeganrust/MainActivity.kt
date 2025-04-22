package ai.baseweight.animeganrust

import ai.baseweight.animeganrust.databinding.ActivityMainBinding
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import java.nio.ByteBuffer
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mainBitmap: Bitmap? = null
    private val API_KEY by lazy { getString(R.string.api_key) }
    private val MODEL_ID by lazy { getString(R.string.model_id) }
    private val API_BASE_URL = "https://stage-api.baseweight.ai/api/models/" // Base URL for the API
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        var external = this.getExternalFilesDir(null)

        binding.getImage.setOnClickListener {
            try {
                val i = Intent(
                    Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                )
                startActivityForResult(i, 0)
            } catch (e: Exception) {
                // Handle exception
            }
        }

        binding.doPredict.setOnClickListener {
            if(mainBitmap != null) {
                val byteCount = mainBitmap!!.byteCount
                // This is critically important, if this is not directly allocated, it will not go
                // past JNI into Rust
                var byteBuffer : ByteBuffer = ByteBuffer.allocateDirect(byteCount)
                mainBitmap!!.copyPixelsToBuffer(byteBuffer)

                // Our JNI returns an integer
                var predictVal : String
                if (external != null) {
                    // Get the path to the downloaded model
                    var path = external.absolutePath
                    if (!File(path).exists()) {
                        Toast.makeText(this, "Model not found", Toast.LENGTH_SHORT).show()
                    }
                    else {
                        predictVal = startPredict(
                            byteBuffer, path,
                            mainBitmap!!.height, mainBitmap!!.width
                        )
                        Log.d("Predict File Output:", predictVal)
                        if (predictVal != "Failed to load model") {
                            mainBitmap = BitmapFactory.decodeFile(predictVal)
                            binding.imageView.setImageBitmap(mainBitmap)
                            binding.imageView.invalidate()
                        }
                        else
                        {
                            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }


        // Use the existing doFetch button for ORT model
        binding.doFetch.setOnClickListener {
            if (external != null) {
                downloadModel(external.absolutePath, MODEL_ID, "downloaded_model.ort")
            } else {
                Toast.makeText(this, "External storage not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadModel(destinationPath: String, modelId: String, filename: String) {
        Toast.makeText(this, "Starting model download...", Toast.LENGTH_SHORT).show()
        
        // First, get the pre-signed URL
        val urlRequest = Request.Builder()
            .url("${API_BASE_URL}${modelId}/download".toHttpUrlOrNull()!!)
            .header("Authorization", "Bearer $API_KEY")
            .build()
        
        client.newCall(urlRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {

                runOnUiThread {
                    Toast.makeText(this@MainActivity, 
                        "Failed to get download URL: ${e.message}", 
                        Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Log.e("MainActivity", "Error getting download URL: ${response.code}")
                        Toast.makeText(this@MainActivity, 
                            "Error getting download URL: ${response.code}", 
                            Toast.LENGTH_LONG).show()
                    }
                    return
                }

                try {
                    // Parse the JSON response to get the pre-signed URL
                    val jsonResponse = response.body?.string()
                    val jsonObject = JSONObject(jsonResponse)
                    val downloadUrl = jsonObject.getString("download_url")
                    
                    // Now download from the pre-signed URL
                    val downloadRequest = Request.Builder()
                        .url(downloadUrl)
                        .build()

                    client.newCall(downloadRequest).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            runOnUiThread {
                                Log.e("MainActivity", "Download failed: ${e.message}")
                                Toast.makeText(this@MainActivity, 
                                    "Download failed: ${e.message}", 
                                    Toast.LENGTH_LONG).show()
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (!response.isSuccessful) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, 
                                        "Download error: ${response.code}", 
                                        Toast.LENGTH_LONG).show()
                                }
                                return
                            }
                            
                            response.body?.let { body ->
                                try {
                                    val modelFile = File(destinationPath, filename)
                                    modelFile.outputStream().use { fileOutputStream ->
                                        body.byteStream().copyTo(fileOutputStream)
                                    }
                                    
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, 
                                            "Model downloaded successfully!", 
                                            Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, 
                                            "Failed to save model: ${e.message}", 
                                            Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    runOnUiThread {
                        Log.e("MainActivity", "Failed to parse response: ${e.message}")
                        Toast.makeText(this@MainActivity, 
                            "Failed to parse response: ${e.message}", 
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    override fun onActivityResult(reqCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(reqCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val imageUri = data!!.data
            val imageStream = contentResolver.openInputStream(imageUri!!)
            val exifStream = contentResolver.openInputStream(imageUri)
            val exif = ExifInterface(exifStream!!)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotMatrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotMatrix.postRotate(270f)
            }
            val selectedImage = BitmapFactory.decodeStream(imageStream)
            val rotatedBitmap = Bitmap.createBitmap(
                selectedImage, 0, 0,
                selectedImage.width, selectedImage.height,
                rotMatrix, true
            )

            // This is really important
            mainBitmap = rotatedBitmap

            runOnUiThread {
                binding.imageView.setImageBitmap(rotatedBitmap);
            }

        } else {
        }
    }

    /**
     * A native method that is implemented by the 'animegangallerydemo' native library,
     * which is packaged with this application.
     */

    external fun startPredict(buffer: ByteBuffer, externalFilePath: String, height: Int, width: Int) : String

    companion object {
        // Used to load the 'animegangallerydemo' library on application startup.
        init {
            System.loadLibrary("animegandemo")
        }
    }
}
