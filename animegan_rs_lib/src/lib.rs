#[cfg(target_os = "android")]
#[allow(non_snake_case)]
pub mod animegandemo {
    extern crate jni;

    use ort::session::{builder::GraphOptimizationLevel, Session};
    use ort::value::Tensor;
    use image::{ImageBuffer, Rgb, RgbImage, DynamicImage, GenericImageView};
    use anyhow::{Result as AnyhowResult, Context};
    use ndarray::ArrayViewD;
    use jni::JNIEnv;
    use jni::objects::{JClass, JObject, JString};
    use jni::sys::{jstring, jint};

    fn preprocess(image: DynamicImage) -> AnyhowResult<Vec<f32>> {
        // Resize to 512x512
        let resized = image.resize_exact(512, 512, image::imageops::FilterType::CatmullRom);
        
        // Convert to RGB and normalize to [-1, 1]
        let rgb = resized.to_rgb8();
        let mut data = Vec::with_capacity(3 * 512 * 512);
        
        // Convert from HWC to CHW format and normalize
        for c in 0..3 {
            for y in 0..512 {
                for x in 0..512 {
                    let pixel = rgb.get_pixel(x, y);
                    // Normalize to [-1, 1]
                    // Note: RgbImage stores channels in RGB order, so we can use c directly
                    data.push((pixel[c] as f32 / 255.0) * 2.0 - 1.0);
                }
            }
        }
        
        Ok(data)
    }
    
    fn postprocess(tensor: &ArrayViewD<f32>) -> AnyhowResult<ImageBuffer<Rgb<u8>, Vec<u8>>> {
        // Create output image
        let mut img = ImageBuffer::new(512, 512);
        
        // Convert from CHW to HWC and denormalize
        for y in 0..512 {
            for x in 0..512 {
                let b = ((tensor[[0, y, x]] + 1.0) * 127.5) as u8;
                let g = ((tensor[[1, y, x]] + 1.0) * 127.5) as u8;
                let r = ((tensor[[2, y, x]] + 1.0) * 127.5) as u8;
                img.put_pixel(x as u32, y as u32, Rgb([r, g, b]));
            }
        }
        
        Ok(img)
    }

    fn predict(img: &RgbImage, path_string: &str) -> AnyhowResult<String> {

        let dynamic_img = DynamicImage::ImageRgb8(img.clone());
        let input_data = preprocess(dynamic_img)?;

        // Create input tensor with proper shape [1, 3, 512, 512]
        let input_tensor = Tensor::from_array(([1, 3, 512, 512], input_data))
            .context("Failed to create input tensor")?;

        // Create model path by joining the external path with model filename
        let model_path = format!("{}/downloaded_model.ort", path_string);

        // Load and run model
        let model = Session::builder()?
        .with_optimization_level(GraphOptimizationLevel::Level3)?
        .with_intra_threads(4)?
        .commit_from_file(model_path)
        .context("Failed to load model")?;

        // Run inference
        let outputs = model.run(ort::inputs!["input.1" => input_tensor]?)
            .context("Failed to run inference")?;

        // Get the output tensor
        let tensor = outputs[0].try_extract_tensor::<f32>()?;
        let tensor_view = tensor.view();
        let output_tensor = tensor_view.remove_axis(ndarray::Axis(0)); // Remove batch dimension

        // Postprocess and save result
        let output_image = postprocess(&output_tensor)?;

        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let output_path_str = format!("{}/anime_gan_output_{}.png", path_string, timestamp);
        // Create output directory if it doesn't exist
        // Save the output image
        output_image.save(&output_path_str)
            .context("Failed to save output image")?;
        
        Ok(output_path_str)
    }
     

    #[unsafe(no_mangle)]
    pub extern "C" fn Java_ai_baseweight_animeganrust_MainActivity_startPredict(
        env: JNIEnv,
        _class: JClass,
        buffer: JObject,
        external_file_path: JString,
        height: jint,
        width: jint
    ) -> jstring {
        // Convert JString to Rust String and keep the JNI string alive
        let jni_string = env.get_string(external_file_path)
            .expect("Failed to get external file path string");
        let path_string = jni_string.to_str()
            .expect("Failed to convert to Rust string");

        // Get direct ByteBuffer access
        let buffer_ptr = env.get_direct_buffer_address(buffer.into())
            .expect("Failed to get buffer address");

        // Create slice from raw buffer pointer
        let buffer_slice = unsafe {
            std::slice::from_raw_parts(
                buffer_ptr as *const u8,
                (height * width * 4) as usize  // Note: 4 bytes per pixel for ARGB8888
            )
        };

        // Convert ARGB8888 (BGRA in memory) to RGB
        let mut rgb_data = Vec::with_capacity((height * width * 3) as usize);
        for i in 0..(height * width) as usize {
            let idx = i * 4;
            // ARGB8888 format: [B, G, R, A]
            rgb_data.push(buffer_slice[idx + 2]); // R
            rgb_data.push(buffer_slice[idx + 1]); // G
            rgb_data.push(buffer_slice[idx]);     // B
        }

        // Create RgbImage from the RGB data
        let img = RgbImage::from_raw(
            width as u32,
            height as u32, 
            rgb_data
        ).expect("Failed to create image from buffer");

        // Run prediction and get output path
        let output_path = match predict(&img, path_string) {
            Ok(path) => path,
            Err(e) => {
                let error_msg = format!("Prediction failed: {}", e);
                return env.new_string(error_msg)
                    .expect("Failed to create error string")
                    .into_raw()
            }
        };
        
        env.new_string(&output_path)
            .expect("Failed to create return string")
            .into_raw()
    }

}
