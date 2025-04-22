# AnimeGanRust

This is a basic project written to show how to do inference with ONNX runtime using Pyke-ORT.  I didn't want to 
use MobileNet because that demo is played out and boring, and while GANs are also out of fashion in favour
of Transformer-based architectures, it still makes for a nice hello world demo in its simplicity.

## Instructions to get working:
* Setup Rust for Android compilation
* Build the Rust project, which is setup to produce a cdylib, which is compatible with Android
* Copy the JNI directory over
* Copy libc++_shared.so, needed by ONNX Runtime, even though we're statically linking it (TODO: Fix this)

## Why Rust?

Since more and more Machine Learning community projects (i.e. Candle, Tokenizers) are being written in Rust, it
made sense to do this exercise.  While I did want to try using Candle with this demo, the fact is that Candle
was unable to handle all the Reshape operations that are required on the model conversion of the AnimeGAN model.

## Why Android?

Because despite what Apple fans will tell you, the majority of devices used on the planet are still Android 
devices, and despite owning an iPhone, I like hacking on Android more.
